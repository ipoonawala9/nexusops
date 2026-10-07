package com.nexusops.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
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
class LeadApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID ownerId;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("lead"));
        owner = Api.login(mvc, ws);
        TestCrm.enable(owner);
        ownerId = UUID.fromString(Api.read(owner.get("/api/v1/me"), "$.user.id"));
    }

    private UUID lead(String json) throws Exception {
        return Api.id(owner.post("/api/v1/leads", json).andExpect(status().isCreated()));
    }

    @Test
    void createsALeadOwnedByItsCreator() throws Exception {
        owner.post("/api/v1/leads", "{\"firstName\":\" Grace \",\"lastName\":\"Hopper\",\"companyName\":\"Acme\","
                        + "\"email\":\" GRACE@Acme.test \",\"phone\":\" +91  98200 41130 \",\"source\":\"REFERRAL\","
                        + "\"estimatedValue\":50000}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Grace Hopper"))
                .andExpect(jsonPath("$.email").value("grace@acme.test"))
                .andExpect(jsonPath("$.phone").value("+91 98200 41130"))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.source").value("REFERRAL"))
                .andExpect(jsonPath("$.owner.id").value(ownerId.toString()))
                .andExpect(jsonPath("$.createdBy.id").value(ownerId.toString()))
                .andExpect(jsonPath("$.estimatedValue").value(50000))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.version").value(0));
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = 'LeadCreated'", Long.class)).isEqualTo(1);
    }

    @Test
    void aCompanyOnlyLeadIsNamedAfterTheCompany() throws Exception {
        UUID id = lead("{\"companyName\":\"Deccan Spices\"}");
        owner.get("/api/v1/leads/" + id).andExpect(jsonPath("$.name").value("Deccan Spices"))
                .andExpect(jsonPath("$.source").value("OTHER"));
        owner.get("/api/v1/leads").andExpect(jsonPath("$.items[0].name").value("Deccan Spices"));
        owner.post("/api/v1/activities", "{\"subjectType\":\"LEAD\",\"subjectId\":\"" + id
                + "\",\"type\":\"NOTE\",\"summary\":\"Called\"}").andExpect(status().isCreated());
        owner.post("/api/v1/tasks", "{\"title\":\"Follow up\",\"subjectType\":\"LEAD\",\"subjectId\":\"" + id + "\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.subject.label").value("Deccan Spices"));
    }

    @Test
    void invalidLeadsAreFieldErrors() throws Exception {
        String[][] cases = {
                {"{}", "lastName"},
                {"{\"jobTitle\":\"CEO\"}", "lastName"},
                {"{\"lastName\":\"X\",\"email\":\"nope\"}", "email"},
                {"{\"lastName\":\"X\",\"estimatedValue\":-1}", "estimatedValue"},
                {"{\"lastName\":\"X\",\"estimatedValue\":1,\"currency\":\"EURO\"}", "currency"},
                {"{\"lastName\":\"X\",\"ownerId\":\"" + UUID.randomUUID() + "\"}", "ownerId"},
                {"{\"lastName\":\"X\",\"description\":\"" + "d".repeat(5001) + "\"}", "description"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/leads", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
    }

    @Test
    void editsWithTheCurrentVersion() throws Exception {
        UUID id = lead("{\"lastName\":\"Iyer\"}");
        owner.put("/api/v1/leads/" + id, "{\"firstName\":\"Meera\",\"lastName\":\"Iyer\",\"source\":\"EVENT\","
                + "\"version\":0}").andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Meera Iyer"))
                .andExpect(jsonPath("$.owner").doesNotExist()); // null on update means unassigned
        owner.put("/api/v1/leads/" + id, "{\"lastName\":\"Iyer\",\"version\":0}").andExpect(status().isConflict());
        owner.put("/api/v1/leads/" + id, "{\"lastName\":\"Iyer\"}").andExpect(status().isBadRequest());
        owner.get("/api/v1/leads/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void statusChangesFollowTheRules() throws Exception {
        UUID id = lead("{\"lastName\":\"Patil\"}");
        owner.post("/api/v1/leads/" + id + "/status", "{\"status\":\"CONTACTED\",\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONTACTED"));
        owner.post("/api/v1/leads/" + id + "/status", "{\"status\":\"DISQUALIFIED\",\"version\":1}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reason"));
        owner.post("/api/v1/leads/" + id + "/status", "{\"status\":\"DISQUALIFIED\",\"reason\":\"No budget\",\"version\":1}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.disqualifyReason").value("No budget"))
                .andExpect(jsonPath("$.disqualifiedAt").exists());
        owner.post("/api/v1/leads/" + id + "/status", "{\"status\":\"QUALIFIED\",\"version\":2}")
                .andExpect(status().isConflict());
        owner.post("/api/v1/leads/" + id + "/status", "{\"status\":\"NEW\",\"version\":2}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.disqualifyReason").doesNotExist())
                .andExpect(jsonPath("$.disqualifiedAt").doesNotExist());
        owner.post("/api/v1/leads/" + id + "/status", "{\"status\":\"CONVERTED\",\"version\":3}")
                .andExpect(status().isBadRequest());
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = 'LeadStatusChanged'", Long.class)).isEqualTo(3);
    }

    @Test
    void listsOpenLeadsByDefaultAndFilters() throws Exception {
        UUID a = lead("{\"firstName\":\"Anjali\",\"lastName\":\"Deshpande\",\"companyName\":\"Deccan\",\"source\":\"WEBSITE\"}");
        UUID b = lead("{\"companyName\":\"Konkan Logistics\",\"email\":\"ops@konkan.test\"}");
        UUID c = lead("{\"lastName\":\"Gone\"}");
        owner.post("/api/v1/leads/" + c + "/status", "{\"status\":\"DISQUALIFIED\",\"reason\":\"Spam\",\"version\":0}")
                .andExpect(status().isOk());
        owner.get("/api/v1/leads").andExpect(jsonPath("$.total").value(2));
        owner.get("/api/v1/leads?status=DISQUALIFIED").andExpect(jsonPath("$.items[*].id").value(Matchers.contains(c.toString())));
        owner.get("/api/v1/leads?q=KONKAN").andExpect(jsonPath("$.items[*].id").value(Matchers.contains(b.toString())));
        owner.get("/api/v1/leads?q=ops@").andExpect(jsonPath("$.total").value(1));
        owner.get("/api/v1/leads?source=WEBSITE").andExpect(jsonPath("$.items[*].id").value(Matchers.contains(a.toString())));
        owner.get("/api/v1/leads?owner=me").andExpect(jsonPath("$.total").value(2));
        owner.get("/api/v1/leads?owner=unassigned").andExpect(jsonPath("$.total").value(0));
        owner.get("/api/v1/leads?status=BOGUS").andExpect(status().isBadRequest());
        owner.get("/api/v1/leads?owner=nobody").andExpect(status().isBadRequest());
    }

    @Test
    void permissionsSplitReadingFromManaging() throws Exception {
        UUID id = lead("{\"lastName\":\"Kulkarni\"}");
        UUID role = TestRoles.create(mvc, owner.session(), "Lead reader", "crm.lead.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        reader.get("/api/v1/leads/" + id).andExpect(status().isOk());
        reader.post("/api/v1/leads", "{\"lastName\":\"X\"}").andExpect(status().isForbidden());
        reader.post("/api/v1/leads/" + id + "/status", "{\"status\":\"CONTACTED\",\"version\":0}").andExpect(status().isForbidden());
        reader.get("/api/v1/crm/owners").andExpect(status().isForbidden());
        owner.get("/api/v1/crm/owners?q=ada").andExpect(status().isOk());
    }
}
