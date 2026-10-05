package com.nexusops.support;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Test-only connections that bypass the application's DataSource. NOTE: FORCE RLS applies to the
 * table owner too, so owner access to tenant tables must go through {@link #ownerAs(UUID)}.
 */
public final class OwnerJdbc {

    private OwnerJdbc() {}

    private static final ConcurrentHashMap<UUID, JdbcTemplate> OWNER_AS = new ConcurrentHashMap<>();
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
     * Single owner connection with app.tenant_id set, for setup/inspection of one tenant's rows.
     * One connection is cached per tenant (the connection is dedicated, so the setting never changes).
     */
    public static JdbcTemplate ownerAs(UUID tenantId) {
        return OWNER_AS.computeIfAbsent(tenantId, id -> {
            var ds = new SingleConnectionDataSource(IntegrationTestSupport.POSTGRES.getJdbcUrl(), "nexusops_owner",
                    IntegrationTestSupport.OWNER_PASSWORD, true);
            var template = new JdbcTemplate(ds);
            template.queryForObject("select set_config('app.tenant_id', ?, false)", String.class, id.toString());
            return template;
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
