package com.nexusops.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHelpDesk;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class TicketApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID customer, ownerId;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("tkt"));
        owner = Api.login(mvc, ws);
        TestHelpDesk.enable(owner);
        customer = Api.id(owner.post("/api/v1/persons", "{\"firstName\":\"Meera\",\"lastName\":\"Iyer\","
                + "\"email\":\"meera@sahyadri.test\"}"));
        ownerId = jdbc().queryForObject("select id from users where email = ?", UUID.class, ws.email());
    }

    private JdbcTemplate jdbc() {
        return OwnerJdbc.ownerAs(ws.tenantId());
    }

    private String body(String extra) {
        return "{\"subject\":\"Printer not printing\",\"description\":\"Paper jams on every page\",\"requesterId\":\""
                + customer + "\"" + extra + "}";
    }

    private UUID ticket(String extra) throws Exception {
        return Api.id(owner.post("/api/v1/helpdesk/tickets", body(extra)).andExpect(status().isCreated()));
    }

    private ResultActions move(UUID id, String status, String note, long version) throws Exception {
        return owner.post("/api/v1/helpdesk/tickets/" + id + "/status", "{\"status\":\"" + status + "\""
                + (note == null ? "" : ",\"note\":\"" + note + "\"") + ",\"version\":" + version + "}");
    }

    private long version(UUID id) throws Exception {
        Integer v = Api.read(owner.get("/api/v1/helpdesk/tickets/" + id), "$.version");
        return v;
    }

    private static Instant instant(Object value) {
        return Instant.parse((String) value);
    }

    private long audits(String action) {
        return jdbc().queryForObject("select count(*) from audit_events where action = ?", Long.class, action);
    }

    @Test
    void createsNumberedTicketsWithSlaDueTimesFromThePriority() throws Exception {
        ResultActions created = owner.post("/api/v1/helpdesk/tickets", body(",\"priority\":\"URGENT\",\"channel\":\"EMAIL\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value("T-00001"))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.priority").value("URGENT"))
                .andExpect(jsonPath("$.channel").value("EMAIL"))
                .andExpect(jsonPath("$.requester.name").value("Meera Iyer"))
                .andExpect(jsonPath("$.assignee").doesNotExist())
                .andExpect(jsonPath("$.sla.firstResponseState").value("ON_TRACK"))
                .andExpect(jsonPath("$.sla.resolutionState").value("ON_TRACK"));
        Instant createdAt = instant(Api.read(created, "$.createdAt"));
        assertThat(instant(Api.read(created, "$.sla.firstResponseDueAt"))).isEqualTo(createdAt.plus(Duration.ofMinutes(60)));
        assertThat(instant(Api.read(created, "$.sla.resolutionDueAt"))).isEqualTo(createdAt.plus(Duration.ofMinutes(240)));
        owner.post("/api/v1/helpdesk/tickets", body("")).andExpect(jsonPath("$.number").value("T-00002"))
                .andExpect(jsonPath("$.priority").value("NORMAL")).andExpect(jsonPath("$.channel").value("PHONE"));
        assertThat(audits("TicketCreated")).isEqualTo(2);
    }

    @Test
    void theCategoryRoutesToItsDefaultAssigneeWhoIsEmailed() throws Exception {
        var agent = members.create(ws.tenantId(), Set.of());
        UUID agentId = jdbc().queryForObject("select id from users where email = ?", UUID.class, agent.email());
        UUID billing = TestHelpDesk.category(owner, "Billing");
        owner.put("/api/v1/helpdesk/categories/" + billing, "{\"name\":\"Billing\",\"defaultAssigneeId\":\"" + agentId
                + "\",\"version\":0}").andExpect(status().isOk());
        owner.post("/api/v1/helpdesk/tickets", body(",\"categoryId\":\"" + billing + "\"")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignee.id").value(agentId.toString()))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.category.name").value("Billing"));
        assertThat(mail.sentTo(agent.email())).singleElement()
                .satisfies(m -> assertThat(m.subject()).isEqualTo("You've been assigned T-00001: Printer not printing"));
        // an explicit assignee wins over the category's
        owner.post("/api/v1/helpdesk/tickets", body(",\"categoryId\":\"" + billing + "\",\"assigneeId\":\"" + ownerId + "\""))
                .andExpect(jsonPath("$.assignee.id").value(ownerId.toString()));
    }

    @Test
    void invalidTicketsAreFieldErrors() throws Exception {
        UUID archivedCategory = TestHelpDesk.category(owner, "Delivery");
        owner.post("/api/v1/helpdesk/categories/" + archivedCategory + "/archive", "").andExpect(status().isOk());
        String[][] cases = {
                {"{\"description\":\"x\",\"requesterId\":\"" + customer + "\"}", "subject"},
                {"{\"subject\":\"" + "x".repeat(201) + "\",\"description\":\"x\",\"requesterId\":\"" + customer + "\"}", "subject"},
                {"{\"subject\":\"x\",\"requesterId\":\"" + customer + "\"}", "description"},
                {"{\"subject\":\"x\",\"description\":\"x\"}", "requesterId"},
                {body(",\"categoryId\":\"" + UUID.randomUUID() + "\""), "categoryId"},
                {body(",\"assigneeId\":\"" + UUID.randomUUID() + "\""), "assigneeId"},
                {body(",\"productId\":\"" + UUID.randomUUID() + "\""), "productId"},
                {body(",\"linkedType\":\"SPACESHIP\",\"linkedId\":\"" + UUID.randomUUID() + "\""), "linkedType"},
                {body(",\"linkedType\":\"PARTY\""), "linkedId"},
                {body(",\"linkedType\":\"PARTY\",\"linkedId\":\"" + UUID.randomUUID() + "\""), "linkedId"},
                {"{\"subject\":\"x\",\"description\":\"x\",\"requesterId\":\"" + UUID.randomUUID() + "\"}", "requesterId"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/helpdesk/tickets", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
        owner.post("/api/v1/helpdesk/tickets", body(",\"categoryId\":\"" + archivedCategory + "\""))
                .andExpect(status().isConflict());
        owner.post("/api/v1/helpdesk/tickets", body(",\"priority\":\"CRITICAL\"")).andExpect(status().isBadRequest());
    }

    @Test
    void linksAProductAndAnyReadableRecord() throws Exception {
        UUID product = Api.id(owner.post("/api/v1/products", "{\"sku\":\"PRN-1\",\"name\":\"Laser printer\"}"));
        UUID org = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Sahyadri Stores\"}"));
        UUID id = ticket(",\"productId\":\"" + product + "\",\"linkedType\":\"PARTY\",\"linkedId\":\"" + org + "\"");
        owner.get("/api/v1/helpdesk/tickets/" + id)
                .andExpect(jsonPath("$.product.sku").value("PRN-1"))
                .andExpect(jsonPath("$.linked.type").value("PARTY"))
                .andExpect(jsonPath("$.linked.label").value("Sahyadri Stores"));
    }

    @Test
    void editsWithItsVersionAndAPriorityChangeRecomputesTheDueTimes() throws Exception {
        UUID id = ticket("");
        Instant createdAt = instant(Api.read(owner.get("/api/v1/helpdesk/tickets/" + id), "$.createdAt"));
        owner.put("/api/v1/helpdesk/tickets/" + id, body(",\"priority\":\"HIGH\",\"version\":1"))
                .andExpect(status().isConflict());
        ResultActions edited = owner.put("/api/v1/helpdesk/tickets/" + id,
                        "{\"subject\":\"Printer jams\",\"description\":\"Every page\",\"requesterId\":\"" + customer
                                + "\",\"priority\":\"HIGH\",\"channel\":\"WALK_IN\",\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Printer jams"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.channel").value("WALK_IN"));
        assertThat(instant(Api.read(edited, "$.sla.firstResponseDueAt"))).isEqualTo(createdAt.plus(Duration.ofMinutes(240)));
        assertThat(instant(Api.read(edited, "$.sla.resolutionDueAt"))).isEqualTo(createdAt.plus(Duration.ofMinutes(1440)));
        owner.put("/api/v1/helpdesk/tickets/" + id, body("")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("version"));
        assertThat(audits("TicketUpdated")).isEqualTo(1);
    }

    @Test
    void assigningEmailsTheAssigneeAndOpensANewTicket() throws Exception {
        var agent = members.create(ws.tenantId(), Set.of());
        UUID agentId = jdbc().queryForObject("select id from users where email = ?", UUID.class, agent.email());
        UUID id = ticket("");
        owner.post("/api/v1/helpdesk/tickets/" + id + "/assign", "{\"assigneeId\":\"" + agentId + "\",\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee.name").value(Matchers.not(Matchers.emptyString())))
                .andExpect(jsonPath("$.status").value("OPEN"));
        assertThat(mail.sentTo(agent.email())).singleElement()
                .satisfies(m -> assertThat(m.textBody()).contains("T-00001", "/app/helpdesk/tickets/" + id));
        owner.post("/api/v1/helpdesk/tickets/" + id + "/assign", "{\"assigneeId\":\"" + UUID.randomUUID() + "\",\"version\":1}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("assigneeId"));
        owner.post("/api/v1/helpdesk/tickets/" + id + "/assign", "{\"assigneeId\":null,\"version\":1}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.assignee").doesNotExist())
                .andExpect(jsonPath("$.status").value("OPEN"));
        assertThat(audits("TicketAssigned")).isEqualTo(2);
    }

    @Test
    void theWorkflowRefusesInvalidMovesAndNeedsAResolutionNote() throws Exception {
        UUID id = ticket("");
        move(id, "CLOSED", null, 0).andExpect(status().isConflict());
        move(id, "RESOLVED", null, 0).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("note"));
        move(id, "RESOLVED", "Replaced the drum", 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolutionNote").value("Replaced the drum"))
                .andExpect(jsonPath("$.sla.resolvedAt").exists())
                .andExpect(jsonPath("$.sla.resolutionState").value("MET"));
        move(id, "CLOSED", null, 1).andExpect(status().isOk()).andExpect(jsonPath("$.closedAt").exists());
        move(id, "OPEN", null, 2).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A closed ticket can't be changed."));
        owner.put("/api/v1/helpdesk/tickets/" + id, body(",\"version\":2")).andExpect(status().isConflict());
        // a closed ticket is an archived record for tasks, documents and activity
        owner.post("/api/v1/activities", "{\"subjectType\":\"TICKET\",\"subjectId\":\"" + id
                + "\",\"type\":\"NOTE\",\"summary\":\"Late note\"}").andExpect(status().isConflict());
        assertThat(audits("TicketStatusChanged")).isEqualTo(2);
    }

    @Test
    void waitingOnTheCustomerDoesNotBreach() throws Exception {
        UUID id = ticket("");
        move(id, "PENDING", null, 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.sla.resolutionState").value("PAUSED"))
                .andExpect(jsonPath("$.sla.pausedAt").exists());
        // pretend: created 3 days ago and answered within the hour; resolution was due 1 day ago; waiting on the
        // customer since 2 days ago (before it was due)
        Instant now = Instant.now();
        Instant created = now.minus(Duration.ofDays(3));
        jdbc().update("""
                update tickets set created_at = ?, resolution_clock_started_at = ?, first_response_due_at = ?,
                       first_responded_at = ?, resolution_due_at = ?, paused_at = ? where id = ?""",
                ts(created), ts(created), ts(created.plus(Duration.ofMinutes(480))), ts(created.plus(Duration.ofHours(1))),
                ts(now.minus(Duration.ofDays(1))), ts(now.minus(Duration.ofDays(2))), id);
        owner.get("/api/v1/helpdesk/tickets/" + id).andExpect(jsonPath("$.sla.resolutionState").value("PAUSED"));
        owner.get("/api/v1/helpdesk/tickets?sla=breached").andExpect(jsonPath("$.items[*].id",
                Matchers.not(Matchers.hasItem(id.toString()))));
        ResultActions resumed = move(id, "OPEN", null, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.sla.resolutionState").value("ON_TRACK"))
                .andExpect(jsonPath("$.sla.pausedAt").doesNotExist());
        // due = 1 day ago + 2 days of waiting = about 1 day from now
        Instant due = instant(Api.read(resumed, "$.sla.resolutionDueAt"));
        assertThat(due).isBetween(now.plus(Duration.ofHours(23)), now.plus(Duration.ofHours(25)));
    }

    @Test
    void reopeningRestartsResolutionAndCountsReopens() throws Exception {
        UUID id = ticket("");
        move(id, "RESOLVED", "Done", 0).andExpect(status().isOk());
        Instant before = Instant.now();
        ResultActions reopened = move(id, "OPEN", null, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.reopenCount").value(1))
                .andExpect(jsonPath("$.sla.resolvedAt").doesNotExist())
                .andExpect(jsonPath("$.resolutionNote").doesNotExist())
                .andExpect(jsonPath("$.sla.firstRespondedAt").doesNotExist());
        Instant due = instant(Api.read(reopened, "$.sla.resolutionDueAt"));
        assertThat(due).isBetween(before.plus(Duration.ofMinutes(2880)).minusSeconds(5),
                Instant.now().plus(Duration.ofMinutes(2880)).plusSeconds(5));
    }

    @Test
    void listsAndFilters() throws Exception {
        UUID mine = ticket(",\"assigneeId\":\"" + ownerId + "\",\"priority\":\"HIGH\"");
        UUID unassigned = ticket("");
        UUID resolved = ticket("");
        move(resolved, "RESOLVED", "Fixed", 0).andExpect(status().isOk());
        owner.get("/api/v1/helpdesk/tickets").andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].number").value("T-00002"));
        owner.get("/api/v1/helpdesk/tickets?status=RESOLVED,CLOSED").andExpect(jsonPath("$.items[*].id")
                .value(Matchers.contains(resolved.toString())));
        owner.get("/api/v1/helpdesk/tickets?assignee=me").andExpect(jsonPath("$.items[*].id")
                .value(Matchers.contains(mine.toString())));
        owner.get("/api/v1/helpdesk/tickets?assignee=unassigned").andExpect(jsonPath("$.items[*].id")
                .value(Matchers.contains(unassigned.toString())));
        owner.get("/api/v1/helpdesk/tickets?priority=HIGH").andExpect(jsonPath("$.total").value(1));
        owner.get("/api/v1/helpdesk/tickets?q=00002").andExpect(jsonPath("$.items[*].id")
                .value(Matchers.contains(unassigned.toString())));
        owner.get("/api/v1/helpdesk/tickets?q=printer").andExpect(jsonPath("$.total").value(2));
        owner.get("/api/v1/helpdesk/tickets?requesterId=" + customer).andExpect(jsonPath("$.total").value(2));
        // first response overdue on one ticket: breached; another almost due: at risk
        jdbc().update("update tickets set first_response_due_at = now() - interval '1 minute' where id = ?", mine);
        jdbc().update("update tickets set created_at = now() - interval '470 minutes', "
                + "first_response_due_at = now() + interval '10 minutes' where id = ?", unassigned);
        owner.get("/api/v1/helpdesk/tickets?sla=breached").andExpect(jsonPath("$.items[*].id")
                .value(Matchers.contains(mine.toString())));
        owner.get("/api/v1/helpdesk/tickets?sla=at_risk").andExpect(jsonPath("$.items[*].id")
                .value(Matchers.contains(unassigned.toString())));
        owner.get("/api/v1/helpdesk/tickets/" + mine).andExpect(jsonPath("$.sla.firstResponseState").value("BREACHED"));
        owner.get("/api/v1/helpdesk/tickets/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void agentsAndPermissions() throws Exception {
        owner.get("/api/v1/helpdesk/agents?q=" + ws.email().substring(0, 4))
                .andExpect(jsonPath("$[*].email").value(Matchers.hasItem(ws.email())));
        UUID id = ticket("");
        UUID readerRole = TestRoles.create(mvc, owner.session(), "Viewer", "helpdesk.ticket.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(readerRole)));
        reader.get("/api/v1/helpdesk/tickets/" + id).andExpect(status().isOk());
        reader.post("/api/v1/helpdesk/tickets", body("")).andExpect(status().isForbidden());
        reader.post("/api/v1/helpdesk/tickets/" + id + "/assign", "{\"assigneeId\":null,\"version\":0}")
                .andExpect(status().isForbidden());
        reader.post("/api/v1/helpdesk/tickets/" + id + "/status", "{\"status\":\"PENDING\",\"version\":0}")
                .andExpect(status().isForbidden());
        UUID blindRole = TestRoles.create(mvc, owner.session(), "Agent without directory", "helpdesk.ticket.read",
                "helpdesk.ticket.manage");
        Api blind = Api.login(mvc, members.create(ws.tenantId(), Set.of(blindRole)));
        blind.post("/api/v1/helpdesk/tickets", body("")).andExpect(status().isForbidden());
    }

    private static java.sql.Timestamp ts(Instant instant) {
        return java.sql.Timestamp.from(instant);
    }
}
