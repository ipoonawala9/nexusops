package com.nexusops.crm;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestCrm;
import com.nexusops.support.TestTenants;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Disabling CRM removes every CRM permission, so every CRM route answers 403 (spec success criterion 4). */
@AutoConfigureMockMvc
class CrmModuleGateIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    @Test
    void everyCrmRouteIsForbiddenWhileTheModuleIsOff() throws Exception {
        Api owner = Api.login(mvc, TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("gate")));
        TestCrm.enable(owner);
        UUID lead = Api.id(owner.post("/api/v1/leads", "{\"lastName\":\"Gate\"}"));
        owner.put("/api/v1/tenant/modules/CRM", "{\"enabled\":false}").andExpect(status().isOk());
        owner.get("/api/v1/leads").andExpect(status().isForbidden());
        owner.get("/api/v1/leads/" + lead).andExpect(status().isForbidden());
        owner.post("/api/v1/leads", "{\"lastName\":\"X\"}").andExpect(status().isForbidden());
        owner.get("/api/v1/opportunities").andExpect(status().isForbidden());
        owner.get("/api/v1/crm/pipeline/stages").andExpect(status().isForbidden());
        owner.get("/api/v1/crm/pipeline/board").andExpect(status().isForbidden());
        owner.get("/api/v1/crm/customers").andExpect(status().isForbidden());
        owner.get("/api/v1/crm/dashboard").andExpect(status().isForbidden());
        owner.get("/api/v1/crm/owners").andExpect(status().isForbidden());
        owner.get("/api/v1/activities?subjectType=LEAD&subjectId=" + lead).andExpect(status().isForbidden());
        TestCrm.enable(owner);
        owner.get("/api/v1/leads/" + lead).andExpect(status().isOk());
    }
}
