package com.nexusops.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Test-only connections that bypass the application's DataSource. NOTE: FORCE RLS applies to the
 * table owner too, so owner access to tenant tables must go through {@link #ownerAs(UUID)}.
 */
public final class OwnerJdbc {

    private OwnerJdbc() {}

    private static volatile JdbcTemplate jdbc;
    private static volatile JdbcTemplate superuser;
    private static volatile JdbcTemplate rawApp;

    /** Owner connection without tenant context: fine for global tables (tenants, plans, ...). */
    public static synchronized JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(new DriverManagerDataSource(
                    IntegrationTestSupport.POSTGRES.getJdbcUrl(), "nexusops_owner", IntegrationTestSupport.OWNER_PASSWORD));
        }
        return jdbc;
    }

    /**
     * Owner connection with app.tenant_id set, for setup/inspection of one tenant's rows. Every
     * operation gets a fresh connection with the setting applied, closed again after the operation,
     * so no connection outlives a call.
     */
    public static JdbcTemplate ownerAs(UUID tenantId) {
        return tenantScoped("nexusops_owner", IntegrationTestSupport.OWNER_PASSWORD, tenantId.toString());
    }

    /** Template over per-operation connections that have app.tenant_id set to {@code tenantSetting} ("" = no tenant). */
    public static JdbcTemplate tenantScoped(String user, String password, String tenantSetting) {
        var target = new DriverManagerDataSource(IntegrationTestSupport.POSTGRES.getJdbcUrl(), user, password);
        return new JdbcTemplate(new DelegatingDataSource(target) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                try (var ps = connection.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
                    ps.setString(1, tenantSetting);
                    ps.execute();
                } catch (SQLException | RuntimeException e) {
                    connection.close();
                    throw e;
                }
                return connection;
            }
        });
    }

    /** Superuser connection — bypasses RLS. Use ONLY to inspect rows (e.g. NULL-tenant audit events). */
    public static synchronized JdbcTemplate superuser() {
        if (superuser == null) {
            var pg = IntegrationTestSupport.POSTGRES;
            superuser = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
        }
        return superuser;
    }

    /** Connection as the runtime role, WITHOUT the TenantAwareDataSource wrapper — raw RLS behaviour. */
    public static synchronized JdbcTemplate rawApp() {
        if (rawApp == null) {
            rawApp = new JdbcTemplate(new DriverManagerDataSource(
                    IntegrationTestSupport.POSTGRES.getJdbcUrl(), "nexusops_app", IntegrationTestSupport.APP_PASSWORD));
        }
        return rawApp;
    }
}
