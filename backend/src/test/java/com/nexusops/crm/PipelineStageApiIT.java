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
class PipelineStageApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("pipe"));
        owner = Api.login(mvc, ws);
        TestCrm.enable(owner);
    }

    private List<String> ids() throws Exception {
        return Api.read(owner.get("/api/v1/crm/pipeline/stages"), "$[*].id");
    }

    @Test
    void aNewWorkspaceStartsWithTheDefaultPipeline() throws Exception {
        owner.get("/api/v1/crm/pipeline/stages").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(Matchers.contains("Prospecting", "Qualification", "Proposal",
                        "Negotiation", "Won", "Lost")))
                .andExpect(jsonPath("$[*].probability").value(Matchers.contains(10, 25, 50, 75, 100, 0)))
                .andExpect(jsonPath("$[*].kind").value(Matchers.contains("OPEN", "OPEN", "OPEN", "OPEN", "WON", "LOST")));
    }

    @Test
    void crmRoutesNeedTheCrmModule() throws Exception {
        Workspace other = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("nocrm"));
        Api api = Api.login(mvc, other);
        api.get("/api/v1/crm/pipeline/stages").andExpect(status().isForbidden());
        api.post("/api/v1/crm/pipeline/stages", "{\"name\":\"X\",\"probability\":5}").andExpect(status().isForbidden());
    }

    @Test
    void ownersHoldTheNewCrmPermissionsAndTheUnusedSeedsAreGone() throws Exception {
        owner.get("/api/v1/me").andExpect(jsonPath("$.permissions", Matchers.hasItems("crm.lead.read", "crm.lead.manage",
                "crm.opportunity.read", "crm.opportunity.manage", "crm.pipeline.manage", "crm.customer.read")));
        owner.get("/api/v1/permissions").andExpect(jsonPath("$[*].code",
                Matchers.not(Matchers.hasItems("crm.customer.create"))))
                .andExpect(jsonPath("$[?(@.code == 'crm.lead.manage')].module").value(Matchers.contains("CRM")));
    }

    @Test
    void addsRenamesAndReordersOpenStages() throws Exception {
        UUID demo = Api.id(owner.post("/api/v1/crm/pipeline/stages", "{\"name\":\"  Demo   booked \",\"probability\":40}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Demo booked"))
                .andExpect(jsonPath("$.kind").value("OPEN")));
        // a new stage goes after the last open stage, before Won and Lost
        owner.get("/api/v1/crm/pipeline/stages").andExpect(jsonPath("$[*].name").value(Matchers.contains(
                "Prospecting", "Qualification", "Proposal", "Negotiation", "Demo booked", "Won", "Lost")));
        owner.put("/api/v1/crm/pipeline/stages/" + demo, "{\"name\":\"Demo\",\"probability\":45,\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Demo"))
                .andExpect(jsonPath("$.probability").value(45));

        List<String> all = ids();
        List<String> open = all.subList(0, 5);
        String reversed = String.join("\",\"", List.of(open.get(4), open.get(3), open.get(2), open.get(1), open.get(0)));
        owner.put("/api/v1/crm/pipeline/stages/order", "{\"stageIds\":[\"" + reversed + "\"]}").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(Matchers.contains("Demo", "Negotiation", "Proposal",
                        "Qualification", "Prospecting", "Won", "Lost")));
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action in ('PipelineStageCreated', 'PipelineStageUpdated',"
                        + " 'PipelineStagesReordered')", Long.class)).isEqualTo(3);
    }

    @Test
    void invalidStagesAreFieldErrors() throws Exception {
        owner.post("/api/v1/crm/pipeline/stages", "{\"name\":\" \",\"probability\":5}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        owner.post("/api/v1/crm/pipeline/stages", "{\"name\":\"X\",\"probability\":101}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("probability"));
        owner.post("/api/v1/crm/pipeline/stages", "{\"name\":\"X\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("probability"));
        owner.post("/api/v1/crm/pipeline/stages", "{\"name\":\"proposal\",\"probability\":5}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void reorderNeedsExactlyTheOpenStages() throws Exception {
        List<String> all = ids();
        owner.put("/api/v1/crm/pipeline/stages/order", "{\"stageIds\":[\"" + all.get(0) + "\"]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("stageIds"));
        String withWon = String.join("\",\"", List.of(all.get(0), all.get(1), all.get(2), all.get(3), all.get(4)));
        owner.put("/api/v1/crm/pipeline/stages/order", "{\"stageIds\":[\"" + withWon + "\"]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("stageIds"));
    }

    @Test
    void wonAndLostAreFixedAndOneOpenStageMustRemain() throws Exception {
        List<String> all = ids();
        owner.delete("/api/v1/crm/pipeline/stages/" + all.get(4)).andExpect(status().isConflict());
        owner.delete("/api/v1/crm/pipeline/stages/" + all.get(5)).andExpect(status().isConflict());
        owner.put("/api/v1/crm/pipeline/stages/" + all.get(4), "{\"name\":\"Closed won\",\"probability\":100,\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("WON"));
        for (int i = 0; i < 3; i++) {
            owner.delete("/api/v1/crm/pipeline/stages/" + all.get(i)).andExpect(status().isNoContent());
        }
        owner.delete("/api/v1/crm/pipeline/stages/" + all.get(3)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The pipeline needs at least one open stage."));
        owner.delete("/api/v1/crm/pipeline/stages/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void atMostTwelveOpenStages() throws Exception {
        for (int i = 0; i < 8; i++) {
            owner.post("/api/v1/crm/pipeline/stages", "{\"name\":\"Step " + i + "\",\"probability\":5}")
                    .andExpect(status().isCreated());
        }
        owner.post("/api/v1/crm/pipeline/stages", "{\"name\":\"One too many\",\"probability\":5}")
                .andExpect(status().isConflict());
    }

    @Test
    void staleVersionsConflict() throws Exception {
        String first = ids().get(0);
        owner.put("/api/v1/crm/pipeline/stages/" + first, "{\"name\":\"A\",\"probability\":10,\"version\":0}")
                .andExpect(status().isOk());
        owner.put("/api/v1/crm/pipeline/stages/" + first, "{\"name\":\"B\",\"probability\":10,\"version\":0}")
                .andExpect(status().isConflict());
        owner.put("/api/v1/crm/pipeline/stages/" + first, "{\"name\":\"B\",\"probability\":10}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("version"));
    }

    @Test
    void readersSeeTheStagesButCannotChangeThem() throws Exception {
        UUID reader = TestRoles.create(mvc, owner.session(), "Sales reader", "crm.opportunity.read");
        Api api = Api.login(mvc, members.create(ws.tenantId(), Set.of(reader)));
        api.get("/api/v1/crm/pipeline/stages").andExpect(status().isOk());
        api.post("/api/v1/crm/pipeline/stages", "{\"name\":\"X\",\"probability\":5}").andExpect(status().isForbidden());
    }
}
