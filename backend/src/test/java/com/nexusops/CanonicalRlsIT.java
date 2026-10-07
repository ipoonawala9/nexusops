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
                + "values (?, ?, ?, 'CUSTOMER', 'ACTIVE', ?, ?)", UUID.randomUUID(), tenantB, party, now, now);
        b.update("insert into products (id, tenant_id, sku, name, kind, unit, created_at, updated_at) "
                + "values (?, ?, 'B-1', 'Beta', 'GOODS', 'each', ?, ?)", UUID.randomUUID(), tenantB, now, now);
        b.update("insert into activities (id, tenant_id, subject_type, subject_id, type, summary, occurred_at, created_at) "
                + "values (?, ?, 'PARTY', ?, 'NOTE', 'x', ?, ?)", UUID.randomUUID(), tenantB, party, now, now);
        b.update("insert into tasks (id, tenant_id, title, status, priority, created_at, updated_at) "
                + "values (?, ?, 'x', 'OPEN', 'NORMAL', ?, ?)", UUID.randomUUID(), tenantB, now, now);
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

    @Test
    void writesIntoAnotherTenantAreRejected() {
        Timestamp now = Timestamp.from(Instant.now());
        assertThatThrownBy(() -> app(tenantA.toString()).update(
                "insert into parties (id, tenant_id, kind, name, name_key, created_at, updated_at) "
                        + "values (?, ?, 'ORGANIZATION', 'Evil', 'evil', ?, ?)", UUID.randomUUID(), tenantB, now, now))
                .isInstanceOf(DataAccessException.class);
        assertThat(app(tenantA.toString()).update("update parties set name = 'Evil' where id = ?", party)).isZero();
        assertThat(app(tenantA.toString()).update("delete from documents where id = ?", document)).isZero();
    }
}
