package com.nexusops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** The database alone isolates every Phase 5 table, independent of application code. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrmRlsIT extends IntegrationTestSupport {

    static final List<String> TABLES = List.of("pipeline_stages", "leads", "opportunities");

    final UUID tenantA = UUID.randomUUID();
    final UUID tenantB = UUID.randomUUID();
    final UUID party = UUID.randomUUID();
    final UUID stage = UUID.randomUUID();
    final UUID lead = UUID.randomUUID();
    final UUID opportunity = UUID.randomUUID();

    @Autowired JdbcTemplate contextStarted;

    @BeforeAll
    void seedTenantB() {
        Timestamp now = Timestamp.from(Instant.now());
        for (UUID t : new UUID[] {tenantA, tenantB}) {
            OwnerJdbc.jdbc().update("insert into tenants (id, slug, name, status, plan_code, created_at, updated_at) "
                    + "values (?, ?, 'T', 'ACTIVE', 'FREE', ?, ?)", t, "crm-" + t.toString().substring(0, 8), now, now);
        }
        JdbcTemplate b = OwnerJdbc.ownerAs(tenantB);
        b.update("insert into parties (id, tenant_id, kind, name, name_key, created_at, updated_at) "
                + "values (?, ?, 'ORGANIZATION', 'Beta', 'beta', ?, ?)", party, tenantB, now, now);
        b.update("insert into pipeline_stages (id, tenant_id, name, name_key, position, probability, kind, created_at, "
                + "updated_at) values (?, ?, 'Open', 'open', 0, 10, 'OPEN', ?, ?)", stage, tenantB, now, now);
        b.update("insert into leads (id, tenant_id, company_name, source, status, created_at, updated_at) "
                + "values (?, ?, 'Beta lead', 'OTHER', 'NEW', ?, ?)", lead, tenantB, now, now);
        b.update("insert into opportunities (id, tenant_id, name, account_id, stage_id, created_at, updated_at) "
                + "values (?, ?, 'Beta deal', ?, ?, ?, ?)", opportunity, tenantB, party, stage, now, now);
    }

    private static JdbcTemplate app(String tenantSetting) {
        return OwnerJdbc.tenantScoped("nexusops_app", APP_PASSWORD, tenantSetting);
    }

    @Test
    void anotherTenantsContextAndNoContextSeeNothing() {
        for (String table : TABLES) {
            assertThat(app(tenantA.toString()).queryForObject("select count(*) from " + table, Long.class)).as(table).isZero();
            assertThat(app("").queryForObject("select count(*) from " + table, Long.class)).as(table).isZero();
            assertThat(app(tenantB.toString()).queryForObject("select count(*) from " + table, Long.class)).as(table)
                    .isPositive();
        }
    }

    private void insertTenantBRow(JdbcTemplate as, String table) {
        Timestamp now = Timestamp.from(Instant.now());
        UUID id = UUID.randomUUID();
        switch (table) {
            case "pipeline_stages" -> as.update("insert into pipeline_stages (id, tenant_id, name, name_key, position, "
                    + "probability, kind, created_at, updated_at) values (?, ?, 'Evil', 'evil', 1, 5, 'OPEN', ?, ?)",
                    id, tenantB, now, now);
            case "leads" -> as.update("insert into leads (id, tenant_id, company_name, source, status, created_at, "
                    + "updated_at) values (?, ?, 'Evil', 'OTHER', 'NEW', ?, ?)", id, tenantB, now, now);
            case "opportunities" -> as.update("insert into opportunities (id, tenant_id, name, account_id, stage_id, "
                    + "created_at, updated_at) values (?, ?, 'Evil', ?, ?, ?, ?)", id, tenantB, party, stage, now, now);
            default -> throw new IllegalArgumentException(table);
        }
    }

    @Test
    void insertsIntoAnotherTenantAreRejectedByRowLevelSecurity() {
        for (String table : TABLES) {
            assertThatThrownBy(() -> insertTenantBRow(app(tenantA.toString()), table)).as(table + " as tenant A")
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> insertTenantBRow(app(""), table)).as(table + " without tenant context")
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void updatesAndDeletesOfAnotherTenantsRowsAffectNothing() {
        record Case(String update, String delete, UUID id) {}
        for (Case c : List.of(
                new Case("update opportunities set name = 'Evil' where id = ?", "delete from opportunities where id = ?",
                        opportunity),
                new Case("update leads set company_name = 'Evil' where id = ?", "delete from leads where id = ?", lead),
                new Case("update pipeline_stages set name = 'Evil' where id = ?",
                        "delete from pipeline_stages where id = ?", stage))) {
            assertThat(app(tenantA.toString()).update(c.update(), c.id())).isZero();
            assertThat(app(tenantA.toString()).update(c.delete(), c.id())).isZero();
        }
        for (String table : TABLES) {
            assertThat(OwnerJdbc.ownerAs(tenantB).queryForObject("select count(*) from " + table, Long.class)).as(table)
                    .isPositive();
        }
    }
}
