package com.nexusops.shared.db;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Tenant isolation relies on PostgreSQL Row-Level Security (ADR-0002). The application refuses to
 * start if its runtime role could bypass or switch off RLS: superusers, BYPASSRLS roles, the schema
 * owner (owners can ALTER TABLE ... DISABLE ROW LEVEL SECURITY), roles allowed to create objects,
 * and members of any of those. There is intentionally no override switch, and the guard is eager
 * even when lazy initialization is enabled.
 */
@Component
@Lazy(false)
class DatabaseRoleGuard implements InitializingBean {

    record RoleInfo(
            String name,
            boolean superuser,
            boolean bypassRls,
            boolean memberOfPrivilegedRole,
            boolean memberOfSchemaOwner,
            boolean canCreateInSchema) {}

    private static final String ROLE_QUERY = """
            select r.rolname,
                   r.rolsuper,
                   r.rolbypassrls,
                   exists (select 1 from pg_roles x
                           where (x.rolsuper or x.rolbypassrls)
                             and x.oid <> r.oid
                             and pg_has_role(r.oid, x.oid, 'MEMBER')),
                   pg_has_role(r.oid, (select nspowner from pg_namespace where nspname = 'public'), 'MEMBER'),
                   has_schema_privilege(r.oid, 'public', 'CREATE')
            from pg_roles r
            where r.rolname = current_user
            """;

    private final JdbcTemplate jdbc;

    DatabaseRoleGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        RoleInfo role = jdbc.queryForObject(ROLE_QUERY, (rs, n) -> new RoleInfo(
                rs.getString(1), rs.getBoolean(2), rs.getBoolean(3),
                rs.getBoolean(4), rs.getBoolean(5), rs.getBoolean(6)));
        check(role);
    }

    static void check(RoleInfo role) {
        if (role.superuser() || role.bypassRls() || role.memberOfPrivilegedRole()
                || role.memberOfSchemaOwner() || role.canCreateInSchema()) {
            throw new IllegalStateException("Refusing to start: runtime database role '" + role.name()
                    + "' is privileged (superuser, BYPASSRLS, schema owner, can create objects, or a member"
                    + " of such a role) and could bypass or disable tenant Row-Level Security. "
                    + "Connect as the unprivileged runtime role (nexusops_app).");
        }
    }
}
