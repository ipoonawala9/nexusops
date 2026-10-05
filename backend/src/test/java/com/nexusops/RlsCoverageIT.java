package com.nexusops;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.support.IntegrationTestSupport;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Guards every future migration: any table that is not explicitly global must have FORCE RLS and a
 * policy. Adding a table without RLS fails this test.
 */
class RlsCoverageIT extends IntegrationTestSupport {

    static final Set<String> GLOBAL_TABLES =
            Set.of("flyway_schema_history", "plans", "modules", "permissions", "tenants");

    static final Set<String> EXPECTED_TENANT_TABLES = Set.of(
            "tenant_modules", "users", "refresh_tokens", "email_verifications",
            "roles", "role_permissions", "user_roles", "audit_events", "invitations");

    @Autowired JdbcTemplate jdbc;

    @Test
    void expectedTenantTablesExist() {
        List<String> tables = jdbc.queryForList(
                "select tablename from pg_tables where schemaname = 'public'", String.class);
        assertThat(tables).containsAll(EXPECTED_TENANT_TABLES).containsAll(GLOBAL_TABLES);
    }

    @Test
    void everyNonGlobalTableForcesRlsAndHasAPolicy() {
        List<String> unprotected = jdbc.queryForList("""
                select c.relname
                from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'public' and c.relkind in ('r', 'p')
                  and not (c.relrowsecurity and c.relforcerowsecurity
                           and exists (select 1 from pg_policies p where p.schemaname = 'public' and p.tablename = c.relname))
                """, String.class);
        assertThat(unprotected).allMatch(GLOBAL_TABLES::contains);
    }
}
