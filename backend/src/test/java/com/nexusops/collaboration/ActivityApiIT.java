package com.nexusops.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ActivityApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID acme;
    UUID widget;

    @BeforeEach
    void records() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("act"));
        owner = Api.login(mvc, ws);
        acme = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Acme\"}"));
        widget = Api.id(owner.post("/api/v1/products", "{\"sku\":\"W-1\",\"name\":\"Widget\"}"));
    }

    private String note(String type, UUID subject, String summary) {
        return "{\"subjectType\":\"%s\",\"subjectId\":\"%s\",\"type\":\"NOTE\",\"summary\":\"%s\",\"body\":\"Details\"}"
                .formatted(type, subject, summary);
    }

    private Api memberWith(String... permissions) throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "R" + UUID.randomUUID().toString().substring(0, 6), permissions);
        return Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
    }

    @Test
    void logsANoteOnAnOrganizationWithTheAuthor() throws Exception {
        owner.post("/api/v1/activities", note("PARTY", acme, "Kick-off call booked")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.subjectType").value("PARTY"))
                .andExpect(jsonPath("$.subjectId").value(acme.toString()))
                .andExpect(jsonPath("$.type").value("NOTE"))
                .andExpect(jsonPath("$.summary").value("Kick-off call booked"))
                .andExpect(jsonPath("$.body").value("Details"))
                .andExpect(jsonPath("$.author.name").value("Ada Owner"))
                .andExpect(jsonPath("$.occurredAt").exists());
    }

    @Test
    void listsTheTimelineNewestFirstPerSubject() throws Exception {
        String yesterday = Instant.now().minus(1, ChronoUnit.DAYS).toString();
        owner.post("/api/v1/activities", """
                {"subjectType":"PARTY","subjectId":"%s","type":"CALL","summary":"Older","occurredAt":"%s"}"""
                .formatted(acme, yesterday)).andExpect(status().isCreated());
        owner.post("/api/v1/activities", note("PARTY", acme, "Newer")).andExpect(status().isCreated());
        owner.post("/api/v1/activities", note("PRODUCT", widget, "About the widget")).andExpect(status().isCreated());
        owner.get("/api/v1/activities?subjectType=PARTY&subjectId=" + acme).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[*].summary", Matchers.contains("Newer", "Older")));
        owner.get("/api/v1/activities?subjectType=PRODUCT&subjectId=" + widget)
                .andExpect(jsonPath("$.items[*].summary", Matchers.contains("About the widget")));
    }

    @Test
    void subjectChecksRunInOrder() throws Exception {
        owner.post("/api/v1/activities", note("ROBOT", acme, "x")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("subjectType"));
        owner.post("/api/v1/activities", "{\"subjectType\":\"PARTY\",\"type\":\"NOTE\",\"summary\":\"x\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("subjectId"));
        owner.post("/api/v1/activities", note("PARTY", UUID.randomUUID(), "x")).andExpect(status().isNotFound());
        owner.get("/api/v1/activities?subjectType=PARTY&subjectId=" + UUID.randomUUID()).andExpect(status().isNotFound());
        owner.post("/api/v1/parties/" + acme + "/archive", "");
        owner.post("/api/v1/activities", note("PARTY", acme, "x")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This record is archived."));
        owner.get("/api/v1/activities?subjectType=PARTY&subjectId=" + acme).andExpect(status().isOk());
    }

    @Test
    void invalidActivitiesAreFieldErrors() throws Exception {
        String future = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        owner.post("/api/v1/activities", """
                {"subjectType":"PARTY","subjectId":"%s","type":"NOTE","summary":"x","occurredAt":"%s"}"""
                .formatted(acme, future)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("occurredAt"));
        owner.post("/api/v1/activities", "{\"subjectType\":\"PARTY\",\"subjectId\":\"%s\",\"summary\":\"x\"}"
                .formatted(acme)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("type"));
        owner.post("/api/v1/activities", note("PARTY", acme, " ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("summary"));
        owner.post("/api/v1/activities", """
                {"subjectType":"PARTY","subjectId":"%s","type":"NOTE","summary":"x","body":"%s"}"""
                .formatted(acme, "b".repeat(10_001))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("body"));
    }

    @Test
    void theSubjectsReadPermissionIsRequiredOnTopOfTheActivityPermission() throws Exception {
        Api productsOnly = memberWith("catalog.product.read", "collaboration.activity.create");
        productsOnly.get("/api/v1/activities?subjectType=PARTY&subjectId=" + acme).andExpect(status().isForbidden());
        productsOnly.post("/api/v1/activities", note("PARTY", acme, "x")).andExpect(status().isForbidden());
        productsOnly.post("/api/v1/activities", note("PRODUCT", widget, "ok")).andExpect(status().isCreated());

        Api partyReader = memberWith("directory.party.read");
        partyReader.get("/api/v1/activities?subjectType=PARTY&subjectId=" + acme).andExpect(status().isOk());
        partyReader.post("/api/v1/activities", note("PARTY", acme, "x")).andExpect(status().isForbidden());
    }

    @Test
    void theTimelineIsAppendOnlyAndBodiesStayOutOfTheAuditLog() throws Exception {
        UUID id = Api.id(owner.post("/api/v1/activities", """
                {"subjectType":"PARTY","subjectId":"%s","type":"NOTE","summary":"Pricing","body":"secret margin 42%%"}"""
                .formatted(acme)));
        var app = OwnerJdbc.tenantScoped("nexusops_app", APP_PASSWORD, ws.tenantId().toString());
        assertThatThrownBy(() -> app.update("update activities set summary = 'x' where id = ?", id))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> app.update("delete from activities where id = ?", id))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
        String audit = OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select coalesce(before::text,'') || coalesce(after::text,'') || coalesce(metadata::text,'') "
                        + "from audit_events where action = 'ActivityLogged' and entity_id = ?", String.class, id.toString());
        assertThat(audit).contains("PARTY").doesNotContain("secret margin");
    }
}
