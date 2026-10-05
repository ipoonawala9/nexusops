package com.nexusops.support;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Test-only connections that bypass the application's DataSource. NOTE: FORCE RLS applies to the
 * table owner too, so owner access to tenant tables must go through {@link #ownerAs(UUID)}.
 */
public final class OwnerJdbc {

    private OwnerJdbc() {}

    /** Owner connection without tenant context: fine for global tables (tenants, plans, ...). */
    public static JdbcTemplate jdbc() {
        var ds = new DriverManagerDataSource(
                IntegrationTestSupport.POSTGRES.getJdbcUrl(), "nexusops_owner", IntegrationTestSupport.OWNER_PASSWORD);
        return new JdbcTemplate(ds);
    }

    /** Single owner connection with app.tenant_id set, for setup/inspection of one tenant's rows. */
    public static JdbcTemplate ownerAs(UUID tenantId) {
        var ds = new SingleConnectionDataSource(IntegrationTestSupport.POSTGRES.getJdbcUrl(), "nexusops_owner",
                IntegrationTestSupport.OWNER_PASSWORD, true);
        var jdbc = new JdbcTemplate(ds);
        jdbc.queryForObject("select set_config('app.tenant_id', ?, false)", String.class, tenantId.toString());
        return jdbc;
    }

    /** Connection as the runtime role, WITHOUT the TenantAwareDataSource wrapper — raw RLS behaviour. */
    public static JdbcTemplate rawApp() {
        var ds = new DriverManagerDataSource(
                IntegrationTestSupport.POSTGRES.getJdbcUrl(), "nexusops_app", IntegrationTestSupport.APP_PASSWORD);
        return new JdbcTemplate(ds);
    }
}
