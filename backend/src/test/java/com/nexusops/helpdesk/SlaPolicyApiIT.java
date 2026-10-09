package com.nexusops.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHelpDesk;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class SlaPolicyApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hdsla"));
        owner = Api.login(mvc, ws);
        TestHelpDesk.enable(owner);
    }

    @Test
    void updatesAPolicyWithItsVersion() throws Exception {
        owner.put("/api/v1/helpdesk/sla-policies/HIGH", "{\"firstResponseMinutes\":120,\"resolutionMinutes\":720,\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.firstResponseMinutes").value(120))
                .andExpect(jsonPath("$.version").value(1));
        owner.put("/api/v1/helpdesk/sla-policies/HIGH", "{\"firstResponseMinutes\":60,\"resolutionMinutes\":720,\"version\":0}")
                .andExpect(status().isConflict());
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = 'SlaPolicyUpdated'", Long.class)).isEqualTo(1);
    }

    @Test
    void targetsMustBePositiveWithinSixtyDaysAndResolutionNotShorterThanFirstResponse() throws Exception {
        String[][] cases = {
                {"{\"firstResponseMinutes\":0,\"resolutionMinutes\":60,\"version\":0}", "firstResponseMinutes"},
                {"{\"resolutionMinutes\":60,\"version\":0}", "firstResponseMinutes"},
                {"{\"firstResponseMinutes\":60,\"resolutionMinutes\":86401,\"version\":0}", "resolutionMinutes"},
                {"{\"firstResponseMinutes\":120,\"resolutionMinutes\":60,\"version\":0}", "resolutionMinutes"},
                {"{\"firstResponseMinutes\":60,\"resolutionMinutes\":120}", "version"},
        };
        for (String[] c : cases) {
            owner.put("/api/v1/helpdesk/sla-policies/LOW", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
        // A path segment that names no priority names no resource (GlobalExceptionHandler.handleTypeMismatch): 404.
        owner.put("/api/v1/helpdesk/sla-policies/CRITICAL", "{\"firstResponseMinutes\":1,\"resolutionMinutes\":2,\"version\":0}")
                .andExpect(status().isNotFound());
    }
}
