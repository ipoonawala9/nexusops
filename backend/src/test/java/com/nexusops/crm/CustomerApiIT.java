package com.nexusops.crm;

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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class CustomerApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID acme;
    List<String> stages;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("cust"));
        owner = Api.login(mvc, ws);
        TestCrm.enable(owner);
        acme = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Acme\"}"));
        owner.put("/api/v1/parties/" + acme + "/roles/CUSTOMER", "{}").andExpect(status().isOk());
        Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Prospect Only\"}"));
        stages = Api.read(owner.get("/api/v1/crm/pipeline/stages"), "$[*].id");
    }

    private UUID deal(String amount, String currency, int stage) throws Exception {
        UUID id = Api.id(owner.post("/api/v1/opportunities", "{\"name\":\"D\",\"accountId\":\"" + acme + "\",\"amount\":"
                + amount + ",\"currency\":\"" + currency + "\"}"));
        if (stage > 0) {
            owner.post("/api/v1/opportunities/" + id + "/stage", "{\"stageId\":\"" + stages.get(stage) + "\",\"version\":0"
                    + (stage == 5 ? ",\"lostReason\":\"Price\"" : "") + "}").andExpect(status().isOk());
        }
        return id;
    }

    @Test
    void listsOnlyCustomersWithTheirDeals() throws Exception {
        deal("20", "USD", 0);
        deal("100", "USD", 4);
        owner.get("/api/v1/crm/customers").andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].party.name").value("Acme"))
                .andExpect(jsonPath("$.items[0].openCount").value(1))
                .andExpect(jsonPath("$.items[0].wonCount").value(1))
                .andExpect(jsonPath("$.items[0].openValue[0].amount").value(20.0))
                .andExpect(jsonPath("$.items[0].wonValue[0].amount").value(100.0));
        owner.get("/api/v1/crm/customers?q=zzz").andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void customerTotalsArePerCurrency() throws Exception {
        deal("100", "USD", 4);
        deal("50", "EUR", 4);
        deal("20", "USD", 2);
        deal("9", "USD", 5);
        owner.get("/api/v1/crm/customers/" + acme).andExpect(status().isOk())
                .andExpect(jsonPath("$.party.name").value("Acme"))
                .andExpect(jsonPath("$.wonCount").value(2))
                .andExpect(jsonPath("$.wonValue[?(@.currency == 'USD')].amount").value(Matchers.contains(100.0)))
                .andExpect(jsonPath("$.wonValue[?(@.currency == 'EUR')].amount").value(Matchers.contains(50.0)))
                .andExpect(jsonPath("$.openCount").value(1))
                .andExpect(jsonPath("$.weightedValue[0].amount").value(10.0)) // 20 × 50 %
                .andExpect(jsonPath("$.lostCount").value(1))
                .andExpect(jsonPath("$.leadCount").value(0));
    }

    @Test
    void countsLeadsConvertedIntoTheCustomer() throws Exception {
        UUID lead = Api.id(owner.post("/api/v1/leads", "{\"companyName\":\"Acme\"}"));
        owner.post("/api/v1/leads/" + lead + "/convert", "{\"organization\":{\"existingId\":\"" + acme + "\"},\"version\":0}")
                .andExpect(status().isOk());
        owner.get("/api/v1/crm/customers/" + acme).andExpect(jsonPath("$.leadCount").value(1));
        owner.get("/api/v1/leads?status=CONVERTED&partyId=" + acme).andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void customersNeedBothCrmAndDirectoryReadAccess() throws Exception {
        owner.get("/api/v1/crm/customers/" + UUID.randomUUID()).andExpect(status().isNotFound());
        UUID crmOnly = TestRoles.create(mvc, owner.session(), "CRM only", "crm.customer.read");
        Api a = Api.login(mvc, members.create(ws.tenantId(), Set.of(crmOnly)));
        a.get("/api/v1/crm/customers").andExpect(status().isForbidden());
        UUID dirOnly = TestRoles.create(mvc, owner.session(), "Directory only", "directory.party.read");
        Api b = Api.login(mvc, members.create(ws.tenantId(), Set.of(dirOnly)));
        b.get("/api/v1/crm/customers/" + acme).andExpect(status().isForbidden());
    }
}
