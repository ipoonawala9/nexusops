package com.nexusops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Proves the database layer alone isolates tenants, independent of application code. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RlsBehaviourIT extends IntegrationTestSupport {

    static final UUID TENANT_A = UUID.randomUUID();
    static final UUID TENANT_B = UUID.randomUUID();
    static final UUID USER_B = UUID.randomUUID();

    /** Injected only to force the Spring context (and so Flyway) to start before seeding. */
    @Autowired JdbcTemplate contextStarted;

    @BeforeAll
    void seed() {
        JdbcTemplate owner = OwnerJdbc.jdbc();
        Timestamp now = Timestamp.from(Instant.now());
        for (UUID t : new UUID[] {TENANT_A, TENANT_B}) {
            owner.update("insert into tenants (id, slug, name, status, plan_code, created_at, updated_at) "
                    + "values (?, ?, 'T', 'ACTIVE', 'FREE', ?, ?)", t, "rls-" + t.toString().substring(0, 8), now, now);
        }
        OwnerJdbc.ownerAs(TENANT_B).update(
                "insert into users (id, tenant_id, email, password_hash, first_name, last_name, status, created_at, updated_at) "
                + "values (?, ?, 'b@b.test', 'x', 'B', 'B', 'ACTIVE', ?, ?)", USER_B, TENANT_B, now, now);
    }

    /** Per-operation connections (closed after each call) with app.tenant_id set; null = no tenant. */
    private static JdbcTemplate appConnectionAs(UUID tenant) {
        return OwnerJdbc.tenantScoped("nexusops_app", APP_PASSWORD, tenant == null ? "" : tenant.toString());
    }

    @Test
    void tenantACannotSeeTenantBUsers() {
        assertThat(appConnectionAs(TENANT_A).queryForObject(
                "select count(*) from users where id = ?", Long.class, USER_B)).isZero();
        assertThat(appConnectionAs(TENANT_B).queryForObject(
                "select count(*) from users where id = ?", Long.class, USER_B)).isOne();
    }

    @Test
    void noTenantContextSeesNothing() {
        JdbcTemplate jdbc = appConnectionAs(null);
        for (String table : RlsCoverageIT.EXPECTED_TENANT_TABLES) {
            assertThat(jdbc.queryForObject("select count(*) from " + table, Long.class)).as(table).isZero();
        }
    }

    @Test
    void cannotInsertRowForAnotherTenant() {
        Timestamp now = Timestamp.from(Instant.now());
        assertThatThrownBy(() -> appConnectionAs(TENANT_A).update(
                "insert into users (id, tenant_id, email, password_hash, first_name, last_name, status, created_at, updated_at) "
                        + "values (?, ?, 'x@x.test', 'x', 'X', 'X', 'ACTIVE', ?, ?)",
                UUID.randomUUID(), TENANT_B, now, now))
                .hasStackTraceContaining("row-level security");
    }

    @Test
    void cannotUpdateAnotherTenantsRows() {
        int updated = appConnectionAs(TENANT_A).update("update users set first_name = 'Hacked' where id = ?", USER_B);
        assertThat(updated).isZero();
    }

    @Test
    void auditEventsAreAppendOnly() {
        JdbcTemplate jdbc = appConnectionAs(TENANT_A);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into audit_events (id, tenant_id, actor_type, action, occurred_at) values (?, ?, 'SYSTEM', 'Test', now())",
                id, TENANT_A);
        assertThatThrownBy(() -> jdbc.update("update audit_events set action = 'Changed' where id = ?", id))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> OwnerJdbc.ownerAs(TENANT_A).update("delete from audit_events where id = ?", id))
                .hasStackTraceContaining("append-only");
    }

    @Test
    void globalCatalogsAreReadOnlyForTheApp() {
        assertThatThrownBy(() -> OwnerJdbc.rawApp().update("insert into modules (code, name) values ('EVIL', 'x')"))
                .hasStackTraceContaining("permission denied");
    }
}
