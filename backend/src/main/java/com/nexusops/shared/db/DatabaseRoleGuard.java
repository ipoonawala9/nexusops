package com.nexusops.shared.db;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Tenant isolation relies on PostgreSQL Row-Level Security (ADR-0002). Superusers and BYPASSRLS
 * roles ignore RLS, so the application refuses to start if its runtime connection uses one.
 * There is intentionally no override switch.
 */
@Component
class DatabaseRoleGuard implements InitializingBean {

    record RoleInfo(String name, boolean superuser, boolean bypassRls) {}

    private final JdbcTemplate jdbc;

    DatabaseRoleGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        RoleInfo role = jdbc.queryForObject(
                "select rolname, rolsuper, rolbypassrls from pg_roles where rolname = current_user",
                (rs, n) -> new RoleInfo(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3)));
        check(role);
    }

    static void check(RoleInfo role) {
        if (role.superuser() || role.bypassRls()) {
            throw new IllegalStateException("Refusing to start: runtime database role '" + role.name()
                    + "' is a superuser or has BYPASSRLS, which would disable tenant Row-Level Security. "
                    + "Connect as the unprivileged runtime role (nexusops_app).");
        }
    }
}
