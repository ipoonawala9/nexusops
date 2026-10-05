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
                "\"/api/v1/auth/logout-all\"", "\"/api/v1/me\"", "\"/api/v1/tenant\"", "\"bearerAuth\"");
        if (Boolean.getBoolean("openapi.export")) {
            Files.writeString(Path.of("../docs/api/openapi.json"), doc);
        }
    }
}
