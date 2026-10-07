package com.nexusops.shared.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The runtime role can execute no function the migration owner creates (V0_2's revoke was a no-op until V14). */
class FunctionPrivilegesIT extends IntegrationTestSupport {

    @Autowired JdbcTemplate contextStarted;

    private static final String OWNER_FUNCTIONS = "from pg_proc p join pg_namespace n on n.oid = p.pronamespace "
            + "where n.nspname = 'public' and p.proowner = 'nexusops_owner'::regrole";

    @Test
    void theRuntimeRoleCanExecuteNoOwnerFunction() {
        List<String> all = OwnerJdbc.jdbc().queryForList("select p.proname " + OWNER_FUNCTIONS, String.class);
        assertThat(all).contains("grant_to_system_roles", "audit_events_immutable");
        List<String> offenders = OwnerJdbc.jdbc().queryForList("select p.proname " + OWNER_FUNCTIONS
                + " and has_function_privilege('nexusops_app', p.oid, 'EXECUTE')", String.class);
        assertThat(offenders).as("owner functions executable by nexusops_app").isEmpty();
    }

    @Test
    void aFunctionCreatedLaterIsNotExecutableByTheRuntimeRole() {
        try {
            OwnerJdbc.jdbc().execute("create function fn_privileges_probe() returns int language sql as 'select 1'");
            assertThat(OwnerJdbc.jdbc().queryForObject(
                    "select has_function_privilege('nexusops_app', 'fn_privileges_probe()', 'EXECUTE')", Boolean.class))
                    .isFalse();
        } finally {
            OwnerJdbc.jdbc().execute("drop function if exists fn_privileges_probe()");
        }
    }
}
