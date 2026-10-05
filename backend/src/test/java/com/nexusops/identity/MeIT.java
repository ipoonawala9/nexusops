package com.nexusops.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestTenants;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class MeIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    @Test
    void meReturnsProfileTenantPermissionsAndModules() throws Exception {
        var ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("me"));
        var session = TestTenants.login(mvc, ws);
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(ws.email()))
                .andExpect(jsonPath("$.user.firstName").value("Ada"))
                .andExpect(jsonPath("$.tenant.slug").value(ws.slug()))
                .andExpect(jsonPath("$.tenant.status").value("ACTIVE"))
                .andExpect(jsonPath("$.permissions", Matchers.hasItem("tenant.settings.update")))
                .andExpect(jsonPath("$.permissions", Matchers.not(Matchers.hasItem("crm.customer.read"))))
                .andExpect(jsonPath("$.modules", Matchers.empty()))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
    }

    @Test
    void meRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.requestId").exists());
    }
}
