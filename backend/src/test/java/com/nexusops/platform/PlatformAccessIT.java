package com.nexusops.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.platform.internal.PlatformAccess;
import com.nexusops.shared.TenantContext;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestTenants;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
class PlatformAccessIT extends IntegrationTestSupport {

    @Autowired PlatformAccess access;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    private static final String INSERT_PLATFORM_USER = """
            insert into platform_users (id, email, password_hash, totp_secret_enc, role, status, created_at, updated_at)
            values (?, ?, 'x', 'v1:x', 'PLATFORM_SUPPORT', 'ACTIVE', ?, ?)""";

    @Test
    void platformTablesAreInvisibleAndUnwritableWithoutTheFlag() {
        String email = "ops-" + UUID.randomUUID() + "@nexusops.test";
        Timestamp now = Timestamp.from(Instant.now());
        access.writeWithoutResult(() -> jdbc.update(INSERT_PLATFORM_USER, UUID.randomUUID(), email, now, now));

        assertThat(OwnerJdbc.rawApp().queryForObject(
                "select count(*) from platform_users where email = ?", Long.class, email)).isZero();
        assertThatThrownBy(() -> OwnerJdbc.rawApp().update(INSERT_PLATFORM_USER, UUID.randomUUID(),
                "x-" + email, now, now)).rootCause().hasMessageContaining("row-level security");
        assertThat(access.read(() -> jdbc.queryForObject(
                "select count(*) from platform_users where email = ?", Long.class, email))).isOne();
    }

    @Test
    void theFlagGivesReadOnlyCrossTenantVisibility() throws Exception {
        UUID a = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("pa-a")).tenantId();
        UUID b = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("pa-b")).tenantId();

        assertThat(access.read(() -> jdbc.queryForObject(
                "select count(*) from users where tenant_id in (?, ?)", Long.class, a, b))).isEqualTo(2);
        assertThat(access.read(() -> jdbc.queryForObject("""
                select count(*) from user_roles ur join roles r on r.id = ur.role_id
                where r.tenant_id in (?, ?) and r.name = 'TENANT_OWNER'""", Long.class, a, b))).isEqualTo(2);
        // The flag never widens writes — including on the join tables whose policies use EXISTS subqueries.
        assertThat(access.write(() -> jdbc.update(
                "update users set first_name = 'Mallory' where tenant_id in (?, ?)", a, b))).isZero();
        assertThat(access.write(() -> jdbc.update("delete from user_roles"))).isZero();
        assertThat(access.write(() -> jdbc.update("delete from role_permissions"))).isZero();
        UUID userOfA = access.read(() -> jdbc.queryForObject("select id from users where tenant_id = ?", UUID.class, a));
        UUID roleOfB = access.read(() -> jdbc.queryForObject(
                "select id from roles where tenant_id = ? and name = 'TENANT_ADMIN'", UUID.class, b));
        assertThatThrownBy(() -> access.writeWithoutResult(() -> jdbc.update(
                "insert into user_roles (user_id, role_id) values (?, ?)", userOfA, roleOfB)))
                .rootCause().hasMessageContaining("row-level security");
        // ...and tenant-scoped access is unchanged (the restated policies are not stricter in-tenant).
        assertThat(OwnerJdbc.ownerAs(a).queryForObject("select count(*) from user_roles", Long.class)).isOne();
    }

    @Test
    void theFlagEndsWithItsTransaction() {
        var single = new SingleConnectionDataSource(IntegrationTestSupport.POSTGRES.getJdbcUrl(), "nexusops_app",
                IntegrationTestSupport.APP_PASSWORD, true);
        try {
            JdbcTemplate one = new JdbcTemplate(single);
            new TransactionTemplate(new DataSourceTransactionManager(single)).executeWithoutResult(s ->
                    one.queryForObject("select set_config('app.platform_access', 'on', true)", String.class));
            assertThat(one.queryForObject(
                    "select coalesce(current_setting('app.platform_access', true), '')", String.class)).isEmpty();
            assertThat(one.queryForObject("select count(*) from users", Long.class)).isZero();
        } finally {
            single.destroy();
        }
    }

    @Test
    void refusesInsideATenantScopeOrAnOpenTransaction() {
        try (var scope = TenantContext.open(UUID.randomUUID(), null)) {
            assertThatThrownBy(() -> access.read(() -> 1)).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> tx.execute(s -> access.read(() -> 1))).isInstanceOf(IllegalStateException.class);
    }
}
