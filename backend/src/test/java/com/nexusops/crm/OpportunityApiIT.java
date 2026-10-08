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
class OpportunityApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID acme, grace;
    List<String> stages; // Prospecting, Qualification, Proposal, Negotiation, Won, Lost

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("opp"));
        owner = Api.login(mvc, ws);
        TestCrm.enable(owner);
        acme = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Acme\"}"));
        grace = Api.id(owner.post("/api/v1/persons", "{\"firstName\":\"Grace\",\"organizationId\":\"" + acme + "\"}"));
        stages = Api.read(owner.get("/api/v1/crm/pipeline/stages"), "$[*].id");
    }

    private UUID deal(String extra) throws Exception {
        return Api.id(owner.post("/api/v1/opportunities", "{\"name\":\"Deal\",\"accountId\":\"" + acme + "\"" + extra + "}")
                .andExpect(status().isCreated()));
    }

    private String move(UUID id, String stage, long version, String reason) {
        return "{\"stageId\":\"" + stage + "\",\"version\":" + version
                + (reason == null ? "" : ",\"lostReason\":\"" + reason + "\"") + "}";
    }

    @Test
    void createsInTheFirstOpenStage() throws Exception {
        owner.post("/api/v1/opportunities", "{\"name\":\" Packaging order \",\"accountId\":\"" + acme
                        + "\",\"contactId\":\"" + grace + "\",\"amount\":120000,\"expectedCloseOn\":\"2026-11-30\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Packaging order"))
                .andExpect(jsonPath("$.account.name").value("Acme"))
                .andExpect(jsonPath("$.contact.name").value("Grace"))
                .andExpect(jsonPath("$.stage.name").value("Prospecting"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.owner").exists())
                .andExpect(jsonPath("$.closedAt").doesNotExist());
    }

    @Test
    void invalidOpportunitiesAreFieldErrors() throws Exception {
        String[][] cases = {
                {"{\"name\":\" \",\"accountId\":\"" + acme + "\"}", "name"},
                {"{\"name\":\"D\"}", "accountId"},
                {"{\"name\":\"D\",\"accountId\":\"" + UUID.randomUUID() + "\"}", "accountId"},
                {"{\"name\":\"D\",\"accountId\":\"" + acme + "\",\"contactId\":\"" + acme + "\"}", "contactId"},
                {"{\"name\":\"D\",\"accountId\":\"" + acme + "\",\"stageId\":\"" + stages.get(4) + "\"}", "stageId"},
                {"{\"name\":\"D\",\"accountId\":\"" + acme + "\",\"stageId\":\"" + UUID.randomUUID() + "\"}", "stageId"},
                {"{\"name\":\"D\",\"accountId\":\"" + acme + "\",\"amount\":-5}", "amount"},
                {"{\"name\":\"D\",\"accountId\":\"" + acme + "\",\"ownerId\":\"" + UUID.randomUUID() + "\"}", "ownerId"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/opportunities", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
        owner.post("/api/v1/parties/" + acme + "/archive", "").andExpect(status().isOk());
        owner.post("/api/v1/opportunities", "{\"name\":\"D\",\"accountId\":\"" + acme + "\"}")
                .andExpect(status().isConflict());
    }

    @Test
    void movesThroughTheStagesAndAWinMakesTheAccountACustomer() throws Exception {
        UUID id = deal(",\"amount\":10");
        owner.post("/api/v1/opportunities/" + id + "/stage", move(id, stages.get(2), 0, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.stage.name").value("Proposal")).andExpect(jsonPath("$.closedAt").doesNotExist());
        owner.post("/api/v1/opportunities/" + id + "/stage", move(id, stages.get(4), 1, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WON")).andExpect(jsonPath("$.closedAt").exists());
        owner.get("/api/v1/parties/" + acme).andExpect(jsonPath("$.roles[?(@.role == 'CUSTOMER')].status")
                .value(Matchers.contains("ACTIVE")));
        owner.post("/api/v1/opportunities/" + id + "/stage", move(id, stages.get(5), 2, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("lostReason"));
        owner.post("/api/v1/opportunities/" + id + "/stage", move(id, stages.get(5), 2, "Chose a competitor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LOST"))
                .andExpect(jsonPath("$.lostReason").value("Chose a competitor"));
        owner.post("/api/v1/opportunities/" + id + "/stage", move(id, stages.get(0), 3, null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.lostReason").doesNotExist()).andExpect(jsonPath("$.closedAt").doesNotExist());
        owner.post("/api/v1/opportunities/" + id + "/stage", move(id, stages.get(1), 0, null))
                .andExpect(status().isConflict());
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = 'OpportunityStageChanged'", Long.class)).isEqualTo(4);
        // a second win doesn't touch the already-active customer role again
        long roleChanges = OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = 'PartyRoleChanged'", Long.class);
        owner.post("/api/v1/opportunities/" + id + "/stage", move(id, stages.get(4), 4, null)).andExpect(status().isOk());
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = 'PartyRoleChanged'", Long.class)).isEqualTo(roleChanges);
    }

    @Test
    void movingToAnUnknownStageIsAFieldError() throws Exception {
        UUID id = deal("");
        UUID demo = Api.id(owner.post("/api/v1/crm/pipeline/stages", "{\"name\":\"Demo\",\"probability\":30}"));
        owner.delete("/api/v1/crm/pipeline/stages/" + demo).andExpect(status().isNoContent());
        owner.post("/api/v1/opportunities/" + id + "/stage", move(id, demo.toString(), 0, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("stageId"));
    }

    @Test
    void aStageWithOpportunitiesCannotBeDeleted() throws Exception {
        deal("");
        owner.delete("/api/v1/crm/pipeline/stages/" + stages.get(0)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Move this stage's opportunities first."));
    }

    @Test
    void anArchivedAccountDoesNotBlockEditingOrMoving() throws Exception {
        UUID id = deal("");
        owner.post("/api/v1/parties/" + acme + "/archive", "").andExpect(status().isOk());
        owner.put("/api/v1/opportunities/" + id, "{\"name\":\"Renamed\",\"accountId\":\"" + acme + "\",\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Renamed"));
        owner.post("/api/v1/opportunities/" + id + "/stage", move(id, stages.get(4), 1, null)).andExpect(status().isOk());
        // archived parties are not made customers
        owner.get("/api/v1/parties/" + acme).andExpect(jsonPath("$.roles", Matchers.empty()));
    }

    @Test
    void editsWithTheCurrentVersionAndKeepTheStage() throws Exception {
        UUID id = deal(",\"amount\":10");
        owner.put("/api/v1/opportunities/" + id, "{\"name\":\"Bigger\",\"accountId\":\"" + acme + "\",\"amount\":20,"
                + "\"currency\":\"eur\",\"stageId\":\"" + stages.get(3) + "\",\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.stage.name").value("Prospecting"));
        owner.put("/api/v1/opportunities/" + id, "{\"name\":\"X\",\"accountId\":\"" + acme + "\",\"version\":0}")
                .andExpect(status().isConflict());
    }

    @Test
    void boardTotalsArePerCurrency() throws Exception {
        deal(",\"amount\":100");
        deal(",\"amount\":50,\"currency\":\"EUR\"");
        deal(",\"amount\":25");
        deal("");
        UUID won = deal(",\"amount\":7");
        owner.post("/api/v1/opportunities/" + won + "/stage", move(won, stages.get(4), 0, null)).andExpect(status().isOk());
        owner.get("/api/v1/crm/pipeline/board").andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[*].stage.name").value(Matchers.contains("Prospecting", "Qualification",
                        "Proposal", "Negotiation", "Won", "Lost")))
                .andExpect(jsonPath("$.columns[0].count").value(4))
                .andExpect(jsonPath("$.columns[0].opportunities.length()").value(4))
                .andExpect(jsonPath("$.columns[0].totals[?(@.currency == 'USD')].amount").value(Matchers.contains(125.0)))
                .andExpect(jsonPath("$.columns[0].totals[?(@.currency == 'EUR')].amount").value(Matchers.contains(50.0)))
                .andExpect(jsonPath("$.columns[0].weighted[?(@.currency == 'USD')].amount").value(Matchers.contains(12.5)))
                .andExpect(jsonPath("$.columns[4].count").value(1))
                .andExpect(jsonPath("$.columns[1].count").value(0));
        owner.get("/api/v1/crm/pipeline/board?owner=me").andExpect(jsonPath("$.columns[0].count").value(4));
        owner.get("/api/v1/crm/pipeline/board?owner=someone").andExpect(status().isBadRequest());
    }

    @Test
    void listsAndFilters() throws Exception {
        UUID a = deal("");
        UUID b = Api.id(owner.post("/api/v1/opportunities", "{\"name\":\"Spices contract\",\"accountId\":\"" + grace + "\"}"));
        owner.post("/api/v1/opportunities/" + a + "/stage", move(a, stages.get(4), 0, null)).andExpect(status().isOk());
        owner.get("/api/v1/opportunities").andExpect(jsonPath("$.total").value(2));
        owner.get("/api/v1/opportunities?status=WON").andExpect(jsonPath("$.items[*].id").value(Matchers.contains(a.toString())));
        owner.get("/api/v1/opportunities?q=spices").andExpect(jsonPath("$.items[*].id").value(Matchers.contains(b.toString())));
        owner.get("/api/v1/opportunities?accountId=" + grace).andExpect(jsonPath("$.total").value(1));
        owner.get("/api/v1/opportunities?stageId=" + stages.get(0)).andExpect(jsonPath("$.total").value(1));
        owner.get("/api/v1/opportunities?status=MAYBE").andExpect(status().isBadRequest());
    }

    @Test
    void opportunitiesTakeActivitiesAndPermissionsAreEnforced() throws Exception {
        UUID id = deal("");
        owner.post("/api/v1/activities", "{\"subjectType\":\"OPPORTUNITY\",\"subjectId\":\"" + id
                + "\",\"type\":\"CALL\",\"summary\":\"Pricing call\"}").andExpect(status().isCreated());
        UUID readerRole = TestRoles.create(mvc, owner.session(), "Deal reader", "crm.opportunity.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(readerRole)));
        reader.get("/api/v1/opportunities/" + id).andExpect(status().isOk());
        reader.post("/api/v1/opportunities", "{\"name\":\"D\",\"accountId\":\"" + acme + "\"}").andExpect(status().isForbidden());
        UUID blindRole = TestRoles.create(mvc, owner.session(), "Deal manager without directory",
                "crm.opportunity.read", "crm.opportunity.manage");
        Api blind = Api.login(mvc, members.create(ws.tenantId(), Set.of(blindRole)));
        blind.post("/api/v1/opportunities", "{\"name\":\"D\",\"accountId\":\"" + acme + "\"}").andExpect(status().isForbidden());
        blind.post("/api/v1/opportunities/" + id + "/stage", move(id, stages.get(1), 0, null)).andExpect(status().isOk());
    }
}
