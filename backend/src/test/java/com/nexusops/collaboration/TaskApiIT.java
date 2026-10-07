package com.nexusops.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class TaskApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID ownerId;
    UUID acme;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("task"));
        owner = Api.login(mvc, ws);
        ownerId = userId(ws.email());
        acme = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Acme\"}"));
    }

    private UUID userId(String email) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select id from users where email = ?", UUID.class, email);
    }

    private record Member(Api api, UUID id, String email) {}

    private Member memberWith(String... permissions) throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "R" + UUID.randomUUID().toString().substring(0, 6), permissions);
        Workspace member = members.create(ws.tenantId(), Set.of(role));
        return new Member(Api.login(mvc, member), userId(member.email()), member.email());
    }

    private UUID task(String json) throws Exception {
        return Api.id(owner.post("/api/v1/tasks", json).andExpect(status().isCreated()));
    }

    @Test
    void createsAnUnassignedTaskWithDefaults() throws Exception {
        owner.post("/api/v1/tasks", "{\"title\":\"  Call   back \"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Call back"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.priority").value("NORMAL"))
                .andExpect(jsonPath("$.assignee").doesNotExist())
                .andExpect(jsonPath("$.subject").doesNotExist())
                .andExpect(jsonPath("$.createdBy.name").value("Ada Owner"))
                .andExpect(jsonPath("$.completedAt").doesNotExist());
    }

    @Test
    void assigningSomeoneElseEmailsThemButAssigningYourselfDoesNot() throws Exception {
        Member grace = memberWith("collaboration.task.read");
        owner.post("/api/v1/tasks", """
                {"title":"Send quote","priority":"HIGH","dueOn":"2026-12-01","assigneeId":"%s",
                 "subjectType":"PARTY","subjectId":"%s"}""".formatted(grace.id(), acme))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignee.id").value(grace.id().toString()))
                .andExpect(jsonPath("$.subject.type").value("PARTY"))
                .andExpect(jsonPath("$.subject.label").value("Acme"))
                .andExpect(jsonPath("$.dueOn").value("2026-12-01"));
        var sent = mail.sentTo(grace.email());
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().subject()).isEqualTo("You've been assigned: Send quote");
        assertThat(sent.getFirst().textBody()).contains("Ada Owner").contains("/app/tasks").contains("2026-12-01");

        task("{\"title\":\"Mine\",\"assigneeId\":\"%s\"}".formatted(ownerId));
        assertThat(mail.sentTo(ws.email()).stream().filter(m -> m.subject().startsWith("You've been assigned"))).isEmpty();
    }

    @Test
    void assigneesMustBeActiveMembersAndSubjectsMustBeWritable() throws Exception {
        Workspace other = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("task"));
        UUID stranger = OwnerJdbc.ownerAs(other.tenantId())
                .queryForObject("select id from users where email = ?", UUID.class, other.email());
        owner.post("/api/v1/tasks", "{\"title\":\"x\",\"assigneeId\":\"%s\"}".formatted(stranger))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("assigneeId"));
        Member gone = memberWith("collaboration.task.read");
        OwnerJdbc.ownerAs(ws.tenantId()).update("update users set status = 'DISABLED' where id = ?", gone.id());
        owner.post("/api/v1/tasks", "{\"title\":\"x\",\"assigneeId\":\"%s\"}".formatted(gone.id()))
                .andExpect(status().isBadRequest());
        owner.post("/api/v1/tasks", "{\"title\":\"x\",\"subjectType\":\"PARTY\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("subjectId"));
        owner.post("/api/v1/parties/" + acme + "/archive", "");
        owner.post("/api/v1/tasks", "{\"title\":\"x\",\"subjectType\":\"PARTY\",\"subjectId\":\"%s\"}".formatted(acme))
                .andExpect(status().isConflict());
        owner.post("/api/v1/tasks", "{\"title\":\" \"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("title"));
    }

    @Test
    void listsFilterByAssigneeStatusSubjectAndTitleAndSortByDueDate() throws Exception {
        Member grace = memberWith("collaboration.task.read");
        task("{\"title\":\"Later\",\"dueOn\":\"2026-12-20\",\"assigneeId\":\"%s\"}".formatted(ownerId));
        task("{\"title\":\"Sooner\",\"dueOn\":\"2026-12-01\",\"assigneeId\":\"%s\"}".formatted(ownerId));
        UUID undated = task("{\"title\":\"Someday\",\"assigneeId\":\"%s\"}".formatted(ownerId));
        task("{\"title\":\"Grace's\",\"assigneeId\":\"%s\",\"subjectType\":\"PARTY\",\"subjectId\":\"%s\"}"
                .formatted(grace.id(), acme));
        task("{\"title\":\"Nobody's\"}");
        owner.post("/api/v1/tasks/" + undated + "/status", "{\"status\":\"DONE\"}").andExpect(status().isOk());

        owner.get("/api/v1/tasks?assignee=me")
                .andExpect(jsonPath("$.items[*].title", Matchers.contains("Sooner", "Later", "Someday")));
        owner.get("/api/v1/tasks?assignee=me&status=OPEN,IN_PROGRESS")
                .andExpect(jsonPath("$.items[*].title", Matchers.contains("Sooner", "Later")));
        owner.get("/api/v1/tasks?assignee=unassigned").andExpect(jsonPath("$.items[*].title", Matchers.contains("Nobody's")));
        owner.get("/api/v1/tasks?assignee=" + grace.id()).andExpect(jsonPath("$.items[*].title", Matchers.contains("Grace's")));
        owner.get("/api/v1/tasks?subjectType=PARTY&subjectId=" + acme)
                .andExpect(jsonPath("$.items[*].title", Matchers.contains("Grace's")));
        owner.get("/api/v1/tasks?q=SOON").andExpect(jsonPath("$.items[*].title", Matchers.contains("Sooner")));
        owner.get("/api/v1/tasks?status=WAITING").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("status"));
        owner.get("/api/v1/tasks?assignee=someone").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("assignee"));
    }

    @Test
    void editingChecksTheVersionAndReassignmentEmailsTheNewAssignee() throws Exception {
        Member grace = memberWith("collaboration.task.read");
        UUID id = task("{\"title\":\"Draft\"}");
        owner.put("/api/v1/tasks/" + id, "{\"title\":\"Final\",\"priority\":\"URGENT\",\"assigneeId\":\"%s\",\"version\":0}"
                .formatted(grace.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Final")).andExpect(jsonPath("$.priority").value("URGENT"))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(mail.sentTo(grace.email())).hasSize(1);
        owner.put("/api/v1/tasks/" + id, "{\"title\":\"Stale\",\"version\":0}").andExpect(status().isConflict());
        owner.put("/api/v1/tasks/" + id, "{\"title\":\"No version\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("version"));
        // saving again without changing the assignee sends nothing new
        owner.put("/api/v1/tasks/" + id, "{\"title\":\"Final!\",\"assigneeId\":\"%s\",\"version\":1}"
                .formatted(grace.id())).andExpect(status().isOk());
        assertThat(mail.sentTo(grace.email())).hasSize(1);
    }

    @Test
    void doneSetsCompletedAtAndReopeningClearsIt() throws Exception {
        UUID id = task("{\"title\":\"Ship\"}");
        owner.post("/api/v1/tasks/" + id + "/status", "{\"status\":\"DONE\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE")).andExpect(jsonPath("$.completedAt").exists());
        owner.post("/api/v1/tasks/" + id + "/status", "{\"status\":\"OPEN\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.completedAt").doesNotExist());
        owner.post("/api/v1/tasks/" + id + "/status", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("status"));
    }

    @Test
    void anAssigneeWithReadOnlyAccessCanMoveTheirOwnTaskButNothingElse() throws Exception {
        Member grace = memberWith("collaboration.task.read");
        UUID hers = task("{\"title\":\"Hers\",\"assigneeId\":\"%s\"}".formatted(grace.id()));
        UUID notHers = task("{\"title\":\"Not hers\"}");
        grace.api().post("/api/v1/tasks/" + hers + "/status", "{\"status\":\"IN_PROGRESS\"}").andExpect(status().isOk());
        grace.api().post("/api/v1/tasks/" + notHers + "/status", "{\"status\":\"DONE\"}").andExpect(status().isForbidden());
        grace.api().put("/api/v1/tasks/" + hers, "{\"title\":\"Mine now\",\"version\":1}").andExpect(status().isForbidden());
        grace.api().post("/api/v1/tasks", "{\"title\":\"New\"}").andExpect(status().isForbidden());
        grace.api().get("/api/v1/tasks/assignees").andExpect(status().isForbidden());
        // grace can't read parties: subject labels stay hidden from her
        UUID onAcme = task("{\"title\":\"On Acme\",\"assigneeId\":\"%s\",\"subjectType\":\"PARTY\",\"subjectId\":\"%s\"}"
                .formatted(grace.id(), acme));
        grace.api().get("/api/v1/tasks/" + onAcme).andExpect(jsonPath("$.subject.id").value(acme.toString()))
                .andExpect(jsonPath("$.subject.label").doesNotExist());
    }

    @Test
    void assigneesListsActiveMembersForManagers() throws Exception {
        Member grace = memberWith("collaboration.task.read");
        owner.get("/api/v1/tasks/assignees").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", Matchers.containsInAnyOrder(ownerId.toString(), grace.id().toString())));
        owner.get("/api/v1/tasks/assignees?q=owner").andExpect(jsonPath("$[*].name", Matchers.contains("Ada Owner")));
        owner.get("/api/v1/tasks/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void auditsEveryChange() throws Exception {
        UUID id = task("{\"title\":\"Audit me\"}");
        owner.put("/api/v1/tasks/" + id, "{\"title\":\"Audited\",\"version\":0}");
        owner.post("/api/v1/tasks/" + id + "/status", "{\"status\":\"DONE\"}");
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForList(
                "select action from audit_events where entity_id = ? order by occurred_at", String.class, id.toString()))
                .containsExactly("TaskCreated", "TaskUpdated", "TaskStatusChanged");
    }

    @Test
    void dueDatesAreCalendarDates() throws Exception {
        owner.post("/api/v1/tasks", "{\"title\":\"x\",\"dueOn\":\"" + LocalDate.of(2027, 2, 28) + "\"}")
                .andExpect(jsonPath("$.dueOn").value("2027-02-28"));
    }
}
