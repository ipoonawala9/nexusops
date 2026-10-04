package com.nexusops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.shared.web.RequestIdFilter;
import com.nexusops.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class PlatformFoundationIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void healthIsUpAndPublic() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void openApiDocumentIsServed() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void unknownApiRouteRequiresAuthenticationAsProblemJson() throws Exception {
        mvc.perform(get("/api/v1/anything").header(RequestIdFilter.HEADER, "it-401"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(RequestIdFilter.HEADER, "it-401"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.requestId").value("it-401"));
    }

    @Test
    void prometheusMetricsAreNotPublic() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    }

    @Test
    void runtimeRoleCannotBypassRls() {
        var row = jdbc.queryForMap(
                "select current_user as name, rolsuper, rolbypassrls from pg_roles where rolname = current_user");
        assertThat(row.get("name")).isEqualTo("nexusops_app");
        assertThat(row.get("rolsuper")).isEqualTo(false);
        assertThat(row.get("rolbypassrls")).isEqualTo(false);
    }

    @Test
    void migrationsRanAsOwner() {
        String owner = jdbc.queryForObject(
                "select tableowner from pg_tables where tablename = 'flyway_schema_history'", String.class);
        assertThat(owner).isEqualTo("nexusops_owner");
    }

    @Test
    void runtimeRoleCannotRunDdl() {
        assertThatThrownBy(() -> jdbc.execute("create table should_fail(id int)"))
                .isInstanceOf(BadSqlGrammarException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
    }
}
