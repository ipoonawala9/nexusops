package com.nexusops.collaboration;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestCrm;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class SearchApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("srch"));
        owner = Api.login(mvc, ws);
        TestCrm.enable(owner);
        UUID org = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Konkan Logistics\",\"domain\":\"konkan.test\"}"));
        owner.post("/api/v1/products", "{\"sku\":\"KON-1\",\"name\":\"Konkan crate\"}").andExpect(status().isCreated());
        owner.post("/api/v1/leads", "{\"companyName\":\"Konkan Traders\"}").andExpect(status().isCreated());
        owner.post("/api/v1/opportunities", "{\"name\":\"Konkan renewal\",\"accountId\":\"" + org + "\"}")
                .andExpect(status().isCreated());
    }

    @Test
    void findsEveryReadableRecordTypeInAFixedOrder() throws Exception {
        owner.get("/api/v1/search?q=KONKAN").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].type").value(Matchers.contains("PARTY", "LEAD", "OPPORTUNITY", "PRODUCT")))
                .andExpect(jsonPath("$[0].label").value("Konkan Logistics"))
                .andExpect(jsonPath("$[0].detail").value("konkan.test"))
                .andExpect(jsonPath("$[2].detail").value("Konkan Logistics"))
                .andExpect(jsonPath("$[3].detail").value("KON-1"));
    }

    @Test
    void findsALeadByItsFullName() throws Exception {
        owner.post("/api/v1/leads", "{\"firstName\":\"Grace\",\"lastName\":\"Hopper\"}").andExpect(status().isCreated());
        owner.get("/api/v1/search?q=grace hopper").andExpect(jsonPath("$[*].type").value(Matchers.contains("LEAD")))
                .andExpect(jsonPath("$[0].label").value("Grace Hopper"));
    }

    @Test
    void returnsAtMostFivePerType() throws Exception {
        for (int i = 0; i < 7; i++) {
            owner.post("/api/v1/organizations", "{\"name\":\"Zebra " + i + "\"}").andExpect(status().isCreated());
        }
        owner.get("/api/v1/search?q=zebra").andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    void onlyTypesTheCallerCanRead() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "Directory reader", "directory.party.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        reader.get("/api/v1/search?q=konkan").andExpect(jsonPath("$[*].type").value(Matchers.contains("PARTY")));
        owner.put("/api/v1/tenant/modules/CRM", "{\"enabled\":false}").andExpect(status().isOk());
        owner.get("/api/v1/search?q=konkan").andExpect(jsonPath("$[*].type").value(Matchers.contains("PARTY", "PRODUCT")));
    }

    @Test
    void queriesAreBoundedAndWildcardsAreLiteral() throws Exception {
        owner.get("/api/v1/search?q=k").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("q"));
        owner.get("/api/v1/search?q=" + "x".repeat(101)).andExpect(status().isBadRequest());
        owner.get("/api/v1/search?q=%25%25").andExpect(jsonPath("$.length()").value(0));
        owner.get("/api/v1/search?q=__").andExpect(jsonPath("$.length()").value(0));
    }
}
