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

/** The database alone isolates every Phase 4 table, independent of application code. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CanonicalRlsIT extends IntegrationTestSupport {

    static final List<String> TABLES = List.of("parties", "party_roles", "products", "activities", "tasks",
            "documents", "document_contents");

    final UUID tenantA = UUID.randomUUID();
    final UUID tenantB = UUID.randomUUID();
    final UUID party = UUID.randomUUID();
    final UUID document = UUID.randomUUID();
    final UUID partyRole = UUID.randomUUID();
    final UUID product = UUID.randomUUID();
    final UUID activity = UUID.randomUUID();
    final UUID task = UUID.randomUUID();

    @Autowired JdbcTemplate contextStarted;

    @BeforeAll
    void seedTenantB() {
        Timestamp now = Timestamp.from(Instant.now());
        for (UUID t : new UUID[] {tenantA, tenantB}) {
            OwnerJdbc.jdbc().update("insert into tenants (id, slug, name, status, plan_code, created_at, updated_at) "
                    + "values (?, ?, 'T', 'ACTIVE', 'FREE', ?, ?)", t, "crls-" + t.toString().substring(0, 8), now, now);
        }
        JdbcTemplate b = OwnerJdbc.ownerAs(tenantB);
        b.update("insert into parties (id, tenant_id, kind, name, name_key, created_at, updated_at) "
                + "values (?, ?, 'ORGANIZATION', 'Beta', 'beta', ?, ?)", party, tenantB, now, now);
        b.update("insert into party_roles (id, tenant_id, party_id, role, status, created_at, updated_at) "
                + "values (?, ?, ?, 'CUSTOMER', 'ACTIVE', ?, ?)", partyRole, tenantB, party, now, now);
        b.update("insert into products (id, tenant_id, sku, name, kind, unit, created_at, updated_at) "
                + "values (?, ?, 'B-1', 'Beta', 'GOODS', 'each', ?, ?)", product, tenantB, now, now);
        b.update("insert into activities (id, tenant_id, subject_type, subject_id, type, summary, occurred_at, created_at) "
                + "values (?, ?, 'PARTY', ?, 'NOTE', 'x', ?, ?)", activity, tenantB, party, now, now);
        b.update("insert into tasks (id, tenant_id, title, status, priority, created_at, updated_at) "
                + "values (?, ?, 'x', 'OPEN', 'NORMAL', ?, ?)", task, tenantB, now, now);
        b.update("insert into documents (id, tenant_id, subject_type, subject_id, file_name, content_type, size_bytes, "
                + "sha256, created_at) values (?, ?, 'PARTY', ?, 'b.txt', 'text/plain', 1, ?, ?)", document, tenantB, party,
                "0".repeat(64), now);
        b.update("insert into document_contents (document_id, tenant_id, content) values (?, ?, ?)", document, tenantB,
                new byte[] {66});
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

    /** Inserts a fully valid row for {@code table} owned by tenant B, run through {@code as}. */
    private void insertTenantBRow(JdbcTemplate as, String table) {
        Timestamp now = Timestamp.from(Instant.now());
        UUID id = UUID.randomUUID();
        switch (table) {
            case "parties" -> as.update("insert into parties (id, tenant_id, kind, name, name_key, created_at, updated_at) "
                    + "values (?, ?, 'ORGANIZATION', 'Evil', 'evil', ?, ?)", id, tenantB, now, now);
            case "party_roles" -> as.update("insert into party_roles (id, tenant_id, party_id, role, status, created_at, "
                    + "updated_at) values (?, ?, ?, 'SUPPLIER', 'ACTIVE', ?, ?)", id, tenantB, party, now, now);
            case "products" -> as.update("insert into products (id, tenant_id, sku, name, kind, unit, created_at, updated_at) "
                    + "values (?, ?, 'EVIL-1', 'Evil', 'GOODS', 'each', ?, ?)", id, tenantB, now, now);
            case "activities" -> as.update("insert into activities (id, tenant_id, subject_type, subject_id, type, summary, "
                    + "occurred_at, created_at) values (?, ?, 'PARTY', ?, 'NOTE', 'evil', ?, ?)", id, tenantB, party, now, now);
            case "tasks" -> as.update("insert into tasks (id, tenant_id, title, status, priority, created_at, updated_at) "
                    + "values (?, ?, 'evil', 'OPEN', 'NORMAL', ?, ?)", id, tenantB, now, now);
            case "documents" -> as.update("insert into documents (id, tenant_id, subject_type, subject_id, file_name, "
                    + "content_type, size_bytes, sha256, created_at) values (?, ?, 'PARTY', ?, 'evil.txt', 'text/plain', 1, "
                    + "?, ?)", id, tenantB, party, "1".repeat(64), now);
            case "document_contents" -> as.update("insert into document_contents (document_id, tenant_id, content) "
                    + "values (?, ?, ?)", document, tenantB, new byte[] {69});
            default -> throw new IllegalArgumentException(table);
        }
    }

    @Test
    void insertsIntoAnotherTenantAreRejectedByRowLevelSecurity() {
        for (String table : TABLES) {
            assertThatThrownBy(() -> insertTenantBRow(app(tenantA.toString()), table)).as(table + " as tenant A")
                    .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("row-level security");
            assertThatThrownBy(() -> insertTenantBRow(app(""), table)).as(table + " without tenant context")
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void updatesAndDeletesOfAnotherTenantsRowsAffectNothing() {
        // table -> {update statement, delete statement, id of tenant B's row}
        record Case(String table, String update, String delete, UUID id) {}
        List<Case> cases = List.of(
                new Case("parties", "update parties set name = 'Evil' where id = ?", "delete from parties where id = ?", party),
                new Case("party_roles", "update party_roles set status = 'INACTIVE' where id = ?",
                        "delete from party_roles where id = ?", partyRole),
                new Case("products", "update products set name = 'Evil' where id = ?", "delete from products where id = ?",
                        product),
                new Case("tasks", "update tasks set title = 'Evil' where id = ?", "delete from tasks where id = ?", task),
                new Case("documents", "update documents set file_name = 'evil.txt' where id = ?",
                        "delete from documents where id = ?", document),
                new Case("document_contents", "update document_contents set content = '\\x45'::bytea where document_id = ?",
                        "delete from document_contents where document_id = ?", document));
        for (Case c : cases) {
            assertThat(app(tenantA.toString()).update(c.update(), c.id())).as(c.table() + " update").isZero();
            assertThat(app(tenantA.toString()).update(c.delete(), c.id())).as(c.table() + " delete").isZero();
        }
        // activities are append-only for the runtime role: no UPDATE or DELETE grant at all
        assertThatThrownBy(() -> app(tenantA.toString()).update("update activities set summary = 'Evil' where id = ?", activity))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> app(tenantA.toString()).update("delete from activities where id = ?", activity))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
        // tenant B's rows survived
        for (String table : TABLES) {
            assertThat(OwnerJdbc.ownerAs(tenantB).queryForObject("select count(*) from " + table, Long.class)).as(table)
                    .isPositive();
        }
    }
}
