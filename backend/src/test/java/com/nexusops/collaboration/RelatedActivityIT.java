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
class RelatedActivityIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID acme;

    private void note(String type, UUID id, String summary) throws Exception {
        owner.post("/api/v1/activities", "{\"subjectType\":\"" + type + "\",\"subjectId\":\"" + id
                + "\",\"type\":\"NOTE\",\"summary\":\"" + summary + "\"}").andExpect(status().isCreated());
    }

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("rel"));
        owner = Api.login(mvc, ws);
        TestCrm.enable(owner);
        acme = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Acme\"}"));
        UUID other = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Other\"}"));
        UUID lead = Api.id(owner.post("/api/v1/leads", "{\"companyName\":\"Acme\"}"));
        note("LEAD", lead, "First call");
        owner.post("/api/v1/leads/" + lead + "/convert", "{\"organization\":{\"existingId\":\"" + acme + "\"},\"version\":0}")
                .andExpect(status().isOk());
        UUID deal = Api.id(owner.post("/api/v1/opportunities", "{\"name\":\"Renewal\",\"accountId\":\"" + acme + "\"}"));
        note("OPPORTUNITY", deal, "Pricing call");
        note("PARTY", acme, "Kick-off");
        note("PARTY", other, "Unrelated");
    }

    @Test
    void theCustomerTimelineIncludesItsLeadsAndOpportunities() throws Exception {
        owner.get("/api/v1/activities?subjectType=PARTY&subjectId=" + acme + "&includeRelated=true")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[*].summary").value(Matchers.contains("Kick-off", "Pricing call", "First call")))
                .andExpect(jsonPath("$.items[1].subject.type").value("OPPORTUNITY"))
                .andExpect(jsonPath("$.items[1].subject.label").value("Renewal"))
                .andExpect(jsonPath("$.items[2].subject.type").value("LEAD"));
        owner.get("/api/v1/activities?subjectType=PARTY&subjectId=" + acme)
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].subject.label").value("Acme"));
    }

    @Test
    void relatedRecordsTheCallerCannotReadAreLeftOut() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "Directory and leads", "directory.party.read", "crm.lead.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        reader.get("/api/v1/activities?subjectType=PARTY&subjectId=" + acme + "&includeRelated=true")
                .andExpect(jsonPath("$.items[*].summary").value(Matchers.contains("Kick-off", "First call")));
    }
}
