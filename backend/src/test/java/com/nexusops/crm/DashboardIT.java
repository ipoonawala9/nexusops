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
import java.time.LocalDate;
import java.time.ZoneId;
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
class DashboardIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID acme;
    List<String> stages;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("dash"));
        owner = Api.login(mvc, ws);
        TestCrm.enable(owner);
        acme = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Acme\"}"));
        stages = Api.read(owner.get("/api/v1/crm/pipeline/stages"), "$[*].id");
    }

    private String today(int plusDays) {
        // the default workspace time zone is UTC (tenants.timezone default)
        return LocalDate.now(ZoneId.of("UTC")).plusDays(plusDays).toString();
    }

    @Test
    void anEmptyWorkspace() throws Exception {
        owner.get("/api/v1/crm/dashboard").andExpect(status().isOk())
                .andExpect(jsonPath("$.leads.open.NEW").value(0))
                .andExpect(jsonPath("$.leads.conversionRate").doesNotExist())
                .andExpect(jsonPath("$.pipeline.stages.length()").value(4))
                .andExpect(jsonPath("$.pipeline.wonThisMonth.count").value(0))
                .andExpect(jsonPath("$.pipeline.closingSoon", Matchers.empty()));
    }

    @Test
    void leadFiguresAndConversionRate() throws Exception {
        Api.id(owner.post("/api/v1/leads", "{\"lastName\":\"A\"}"));
        Api.id(owner.post("/api/v1/leads", "{\"lastName\":\"B\"}"));
        UUID contacted = Api.id(owner.post("/api/v1/leads", "{\"lastName\":\"C\"}"));
        owner.post("/api/v1/leads/" + contacted + "/status", "{\"status\":\"CONTACTED\",\"version\":0}").andExpect(status().isOk());
        UUID lost = Api.id(owner.post("/api/v1/leads", "{\"lastName\":\"D\"}"));
        owner.post("/api/v1/leads/" + lost + "/status", "{\"status\":\"DISQUALIFIED\",\"reason\":\"No\",\"version\":0}")
                .andExpect(status().isOk());
        UUID won = Api.id(owner.post("/api/v1/leads", "{\"companyName\":\"Acme\"}"));
        owner.post("/api/v1/leads/" + won + "/convert", "{\"organization\":{\"existingId\":\"" + acme + "\"},\"version\":0}")
                .andExpect(status().isOk());
        owner.get("/api/v1/crm/dashboard").andExpect(jsonPath("$.leads.open.NEW").value(2))
                .andExpect(jsonPath("$.leads.open.CONTACTED").value(1))
                .andExpect(jsonPath("$.leads.open.QUALIFIED").value(0))
                .andExpect(jsonPath("$.leads.newLast30Days").value(5))
                .andExpect(jsonPath("$.leads.converted90Days").value(1))
                .andExpect(jsonPath("$.leads.disqualified90Days").value(1))
                .andExpect(jsonPath("$.leads.conversionRate").value(0.5));
    }

    @Test
    void pipelineFigures() throws Exception {
        String open = "{\"name\":\"Soon\",\"accountId\":\"" + acme + "\",\"amount\":100,\"expectedCloseOn\":\"" + today(5) + "\"}";
        Api.id(owner.post("/api/v1/opportunities", open));
        Api.id(owner.post("/api/v1/opportunities", "{\"name\":\"Later\",\"accountId\":\"" + acme
                + "\",\"amount\":40,\"currency\":\"EUR\",\"expectedCloseOn\":\"" + today(40) + "\"}"));
        UUID won = Api.id(owner.post("/api/v1/opportunities", "{\"name\":\"Won\",\"accountId\":\"" + acme
                + "\",\"amount\":30,\"expectedCloseOn\":\"" + today(3) + "\"}"));
        owner.post("/api/v1/opportunities/" + won + "/stage", "{\"stageId\":\"" + stages.get(4) + "\",\"version\":0}")
                .andExpect(status().isOk());
        UUID lost = Api.id(owner.post("/api/v1/opportunities", "{\"name\":\"Lost\",\"accountId\":\"" + acme + "\"}"));
        owner.post("/api/v1/opportunities/" + lost + "/stage", "{\"stageId\":\"" + stages.get(5)
                + "\",\"lostReason\":\"Price\",\"version\":0}").andExpect(status().isOk());

        owner.get("/api/v1/crm/dashboard").andExpect(jsonPath("$.pipeline.stages[0].count").value(2))
                .andExpect(jsonPath("$.pipeline.stages[0].totals[?(@.currency == 'USD')].amount").value(Matchers.contains(100.0)))
                .andExpect(jsonPath("$.pipeline.stages[0].totals[?(@.currency == 'EUR')].amount").value(Matchers.contains(40.0)))
                .andExpect(jsonPath("$.pipeline.stages[0].weighted[?(@.currency == 'USD')].amount").value(Matchers.contains(10.0)))
                .andExpect(jsonPath("$.pipeline.wonThisMonth.count").value(1))
                .andExpect(jsonPath("$.pipeline.wonThisMonth.totals[0].amount").value(30.0))
                .andExpect(jsonPath("$.pipeline.lostThisMonth.count").value(1))
                .andExpect(jsonPath("$.pipeline.closingSoon[*].name").value(Matchers.contains("Soon")));
    }

    @Test
    void sectionsFollowPermissionsAndOwnerFilters() throws Exception {
        UUID leadsOnly = TestRoles.create(mvc, owner.session(), "Leads only", "crm.lead.read");
        Api a = Api.login(mvc, members.create(ws.tenantId(), Set.of(leadsOnly)));
        a.get("/api/v1/crm/dashboard").andExpect(status().isOk()).andExpect(jsonPath("$.pipeline").doesNotExist())
                .andExpect(jsonPath("$.leads").exists());
        UUID none = TestRoles.create(mvc, owner.session(), "No CRM", "directory.party.read");
        Api b = Api.login(mvc, members.create(ws.tenantId(), Set.of(none)));
        b.get("/api/v1/crm/dashboard").andExpect(status().isForbidden());

        Api.id(owner.post("/api/v1/leads", "{\"lastName\":\"Mine\"}"));
        UUID salesRole = TestRoles.create(mvc, owner.session(), "Sales", "crm.lead.read", "crm.lead.manage");
        Api seller = Api.login(mvc, members.create(ws.tenantId(), Set.of(salesRole)));
        Api.id(seller.post("/api/v1/leads", "{\"lastName\":\"Theirs\"}"));
        seller.get("/api/v1/crm/dashboard?owner=me").andExpect(jsonPath("$.leads.open.NEW").value(1));
        seller.get("/api/v1/crm/dashboard?owner=all").andExpect(jsonPath("$.leads.open.NEW").value(2));
        seller.get("/api/v1/crm/dashboard?owner=x").andExpect(status().isBadRequest());
    }
}
