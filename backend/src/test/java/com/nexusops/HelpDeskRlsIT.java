package com.nexusops;

import static com.nexusops.support.PostgresAssertions.assertDeniedByPostgres;
import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The database alone isolates every Phase 7 table, independent of application code. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HelpDeskRlsIT extends IntegrationTestSupport {

    static final List<String> TABLES = List.of("ticket_categories", "sla_policies", "tickets", "ticket_messages",
            "kb_articles");

    final UUID tenantA = UUID.randomUUID();
    final UUID tenantB = UUID.randomUUID();
    final UUID party = UUID.randomUUID();
    final UUID category = UUID.randomUUID();
    final UUID policy = UUID.randomUUID();
    final UUID ticket = UUID.randomUUID();
    final UUID message = UUID.randomUUID();
    final UUID article = UUID.randomUUID();
    final AtomicInteger counter = new AtomicInteger();

    @Autowired JdbcTemplate contextStarted; // starts the application (and Flyway) before @BeforeAll runs

    @BeforeAll
    void seedTenantB() {
        Timestamp now = Timestamp.from(Instant.now());
        for (UUID t : new UUID[] {tenantA, tenantB}) {
            OwnerJdbc.jdbc().update("insert into tenants (id, slug, name, status, plan_code, created_at, updated_at) "
                    + "values (?, ?, 'T', 'ACTIVE', 'FREE', ?, ?)", t, "hd-" + t.toString().substring(0, 8), now, now);
        }
        JdbcTemplate b = OwnerJdbc.ownerAs(tenantB);
        b.update("insert into parties (id, tenant_id, kind, name, name_key, created_at, updated_at) "
                + "values (?, ?, 'ORGANIZATION', 'Beta', 'beta', ?, ?)", party, tenantB, now, now);
        b.update("insert into ticket_categories (id, tenant_id, name, name_key, position, created_at, updated_at) "
                + "values (?, ?, 'Beta cat', 'beta cat', 0, ?, ?)", category, tenantB, now, now);
        b.update("insert into sla_policies (id, tenant_id, priority, first_response_minutes, resolution_minutes, updated_at) "
                + "values (?, ?, 'LOW', 60, 120, ?)", policy, tenantB, now);
        b.update(TICKET_INSERT, ticket, tenantB, "T-00001", party, category, now, now, now, now, now);
        b.update("insert into ticket_messages (id, tenant_id, ticket_id, kind, body, created_at) "
                + "values (?, ?, ?, 'INTERNAL_NOTE', 'secret', ?)", message, tenantB, ticket, now);
        b.update("insert into kb_articles (id, tenant_id, title, body, status, created_at, updated_at) "
                + "values (?, ?, 'Beta article', 'Body', 'DRAFT', ?, ?)", article, tenantB, now, now);
    }

    static final String TICKET_INSERT = """
            insert into tickets (id, tenant_id, number, subject, description, requester_id, category_id, priority,
                                 channel, status, first_response_due_at, resolution_due_at,
                                 resolution_clock_started_at, created_at, updated_at)
            values (?, ?, ?, 'Beta ticket', 'Desc', ?, ?, 'LOW', 'PHONE', 'NEW', ?, ?, ?, ?, ?)""";

    private static JdbcTemplate app(String tenantSetting) {
        return OwnerJdbc.tenantScoped("nexusops_app", APP_PASSWORD, tenantSetting);
    }

    @Test
    void anotherTenantsContextAndNoContextSeeNothing() {
        for (String table : TABLES) {
            assertThat(app(tenantA.toString()).queryForObject("select count(*) from " + table, Long.class)).as(table)
                    .isZero();
            assertThat(app("").queryForObject("select count(*) from " + table, Long.class)).as(table).isZero();
            assertThat(app(tenantB.toString()).queryForObject("select count(*) from " + table, Long.class)).as(table)
                    .isPositive();
        }
    }

    /** Valid for tenant B (fresh keys each call), so a rejection for anyone else is RLS alone. */
    private void insertTenantBRow(JdbcTemplate as, String table) {
        Timestamp now = Timestamp.from(Instant.now());
        int n = counter.incrementAndGet();
        UUID id = UUID.randomUUID();
        switch (table) {
            case "ticket_categories" -> as.update("insert into ticket_categories (id, tenant_id, name, name_key, position, "
                    + "created_at, updated_at) values (?, ?, ?, ?, 1, ?, ?)", id, tenantB, "Cat " + n, "cat " + n, now, now);
            case "sla_policies" -> {
                String priority = List.of("NORMAL", "HIGH", "URGENT").get(n % 3);
                as.update("delete from sla_policies where tenant_id = ? and priority = ?", tenantB, priority);
                as.update("insert into sla_policies (id, tenant_id, priority, first_response_minutes, resolution_minutes, "
                        + "updated_at) values (?, ?, ?, 60, 120, ?)", id, tenantB, priority, now);
            }
            case "tickets" -> as.update(TICKET_INSERT, id, tenantB, "T-9" + String.format("%04d", n), party, category,
                    now, now, now, now, now);
            case "ticket_messages" -> as.update("insert into ticket_messages (id, tenant_id, ticket_id, kind, body, "
                    + "created_at) values (?, ?, ?, 'INTERNAL_NOTE', 'x', ?)", id, tenantB, ticket, now);
            case "kb_articles" -> as.update("insert into kb_articles (id, tenant_id, title, body, status, created_at, "
                    + "updated_at) values (?, ?, 'x', 'y', 'DRAFT', ?, ?)", id, tenantB, now, now);
            default -> throw new IllegalArgumentException(table);
        }
    }

    @Test
    void insertsIntoAnotherTenantAreRejectedByRowLevelSecurity() {
        for (String table : TABLES) {
            if (table.equals("sla_policies")) {
                // the insert's own delete would match nothing outside tenant B; only the insert can be refused
                assertDeniedByPostgres(() -> app(tenantA.toString()).update("insert into sla_policies (id, tenant_id, "
                        + "priority, first_response_minutes, resolution_minutes, updated_at) values (?, ?, 'HIGH', 1, 2, now())",
                        UUID.randomUUID(), tenantB), table + " as tenant A");
                assertDeniedByPostgres(() -> app("").update("insert into sla_policies (id, tenant_id, priority, "
                        + "first_response_minutes, resolution_minutes, updated_at) values (?, ?, 'HIGH', 1, 2, now())",
                        UUID.randomUUID(), tenantB), table + " without tenant context");
            } else {
                assertDeniedByPostgres(() -> insertTenantBRow(app(tenantA.toString()), table), table + " as tenant A");
                assertDeniedByPostgres(() -> insertTenantBRow(app(""), table), table + " without tenant context");
            }
            insertTenantBRow(app(tenantB.toString()), table); // positive control
        }
    }

    @Test
    void updatesAndDeletesOfAnotherTenantsRowsAffectNothing() {
        record Case(String table, String update, String sameValue, String delete, UUID id) {}
        List<Case> cases = List.of(
                new Case("ticket_categories", "update ticket_categories set name = 'Evil' where id = ?",
                        "update ticket_categories set name = name where id = ?",
                        "delete from ticket_categories where id = ?", category),
                new Case("sla_policies", "update sla_policies set first_response_minutes = 1 where id = ?",
                        "update sla_policies set first_response_minutes = first_response_minutes where id = ?",
                        "delete from sla_policies where id = ?", policy),
                new Case("tickets", "update tickets set subject = 'Evil' where id = ?",
                        "update tickets set subject = subject where id = ?", "delete from tickets where id = ?", ticket),
                new Case("kb_articles", "update kb_articles set title = 'Evil' where id = ?",
                        "update kb_articles set title = title where id = ?", "delete from kb_articles where id = ?",
                        article));
        for (Case c : cases) {
            // positive control: the owning tenant reaches the very same row with the very same statement shape
            assertThat(app(tenantB.toString()).update(c.sameValue(), c.id())).as(c.table() + " as tenant B").isEqualTo(1);
            assertThat(app(tenantA.toString()).update(c.update(), c.id())).as(c.table() + " update").isZero();
            assertThat(app(tenantA.toString()).update(c.delete(), c.id())).as(c.table() + " delete").isZero();
            assertThat(OwnerJdbc.ownerAs(tenantB).queryForObject("select count(*) from " + c.table() + " where id = ?",
                    Long.class, c.id())).as(c.table() + " still exists").isEqualTo(1);
        }
        assertThat(OwnerJdbc.ownerAs(tenantB).queryForObject("select subject from tickets where id = ?", String.class,
                ticket)).isEqualTo("Beta ticket");
    }

    @Test
    void theConversationIsAppendOnlyEvenForItsOwnTenant() {
        assertDeniedByPostgres(() -> app(tenantB.toString()).update("update ticket_messages set body = 'edited' "
                + "where id = ?", message), "update ticket_messages");
        assertDeniedByPostgres(() -> app(tenantB.toString()).update("delete from ticket_messages where id = ?", message),
                "delete ticket_messages");
        assertThat(OwnerJdbc.ownerAs(tenantB).queryForObject("select body from ticket_messages where id = ?",
                String.class, message)).isEqualTo("secret");
    }
}
