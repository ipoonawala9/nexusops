package com.nexusops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.IntegrationTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Keeps the published API contract honest (Claude Code rule §41.11). */
@AutoConfigureMockMvc
class OpenApiContractIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;

    @Test
    void documentListsTheV1RoutesAndBearerScheme() throws Exception {
        String doc = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(doc).contains("\"/api/v1/auth/signup\"", "\"/api/v1/auth/login\"", "\"/api/v1/auth/refresh\"",
                "\"/api/v1/auth/logout-all\"", "\"/api/v1/me\"", "\"/api/v1/tenant\"", "\"bearerAuth\"",
                "\"/api/v1/users\"", "\"/api/v1/users/{id}/roles\"", "\"/api/v1/roles/{id}/permissions\"",
                "\"/api/v1/permissions\"", "\"/api/v1/invitations\"", "\"/api/v1/invitations/accept\"",
                "\"/api/v1/invitations/preview\"", "\"/api/v1/tenant/modules/{code}\"", "\"/api/v1/audit-events\"",
                "\"/api/v1/platform/auth/login\"", "\"/api/v1/platform/auth/refresh\"",
                "\"/api/v1/platform/auth/logout\"", "\"/api/v1/platform/me\"", "\"/api/v1/platform/tenants\"",
                "\"/api/v1/platform/tenants/{id}/suspend\"", "\"/api/v1/platform/tenants/{id}/reactivate\"",
                "\"/api/v1/parties\"", "\"/api/v1/parties/{id}\"", "\"/api/v1/parties/{id}/roles/{role}\"",
                "\"/api/v1/persons\"", "\"/api/v1/organizations\"", "\"/api/v1/products\"", "\"/api/v1/products/{id}\"",
                "\"/api/v1/activities\"", "\"/api/v1/tasks\"", "\"/api/v1/tasks/{id}/status\"",
                "\"/api/v1/tasks/assignees\"", "\"/api/v1/documents\"", "\"/api/v1/documents/{id}/content\"");
        // hierarchy rules (ADR-0004): no changing a role, or disabling/re-enabling/re-roling a user, stronger than the caller
        for (String op : new String[] {"$.paths['/api/v1/roles/{id}'].patch", "$.paths['/api/v1/roles/{id}'].delete",
                "$.paths['/api/v1/roles/{id}/permissions'].put", "$.paths['/api/v1/users/{id}'].patch",
                "$.paths['/api/v1/users/{id}/roles'].put"}) {
            java.util.Map<String, Object> responses = com.jayway.jsonpath.JsonPath.read(doc, op + ".responses");
            assertThat(responses).as(op).containsKey("403");
            assertThat(responses.keySet()).as(op).containsAnyOf("200", "204");
        }
        if (Boolean.getBoolean("openapi.export")) {
            Files.writeString(Path.of("../docs/api/openapi.json"), doc);
        }
    }
}
