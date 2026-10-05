package com.nexusops.audit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Session;
import com.nexusops.support.TestTenants.Workspace;
import java.time.Instant;
import java.util.Set;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class AuditApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Session owner;

    @BeforeEach
    void activity() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("aud"));
        owner = TestTenants.login(mvc, ws);
        mvc.perform(patch("/api/v1/tenant").header("Authorization", "Bearer " + owner.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Audited Co\"}")).andExpect(status().isOk());
        TestRoles.create(mvc, owner, "Auditors", "audit.event.read");
    }

    private ResultActions search(Session s, String... params) throws Exception {
        var request = get("/api/v1/audit-events").header("Authorization", "Bearer " + s.accessToken());
        for (int i = 0; i < params.length; i += 2) request.param(params[i], params[i + 1]);
        return mvc.perform(request);
    }

    @Test
    void listsTheTenantsEventsNewestFirst() throws Exception {
        search(owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].action").value("RoleCreated"))
                .andExpect(jsonPath("$.items[*].action", Matchers.hasItems("TenantSettingsUpdated", "LoginSucceeded",
                        "TenantCreated")))
                .andExpect(jsonPath("$.items[0].after.name").value("Auditors"))
                .andExpect(jsonPath("$.total", Matchers.greaterThanOrEqualTo(5)));
    }

    @Test
    void filtersAndPages() throws Exception {
        search(owner, "action", "TenantSettingsUpdated").andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].before.name").value(ws.slug() + " Inc"));
        search(owner, "entityType", "Role").andExpect(jsonPath("$.items[*].entityType", Matchers.everyItem(Matchers.is("Role"))));
        search(owner, "from", Instant.now().plusSeconds(60).toString()).andExpect(jsonPath("$.total").value(0));
        search(owner, "size", "1").andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void invalidFiltersAreFieldErrors() throws Exception {
        search(owner, "action", "drop table").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("action"));
        search(owner, "from", "yesterday").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("from"));
        search(owner, "from", "2026-10-05T10:00:00Z", "to", "2026-10-04T10:00:00Z").andExpect(status().isBadRequest());
    }

    @Test
    void mistypedQueryParametersAre400FieldErrorsNot404() throws Exception {
        search(owner, "actorId", "not-a-uuid").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.errors[0].field").value("actorId"))
                .andExpect(jsonPath("$.errors[0].message").exists());
        search(owner, "page", "abc").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("page"));
        mvc.perform(get("/api/v1/users").param("size", "x").header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));
        // a malformed UUID in the path is still "no such resource"
        mvc.perform(get("/api/v1/roles/not-a-uuid").header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void requiresTheAuditPermission() throws Exception {
        var reader = TestRoles.create(mvc, owner, "Readers", "identity.user.read");
        Session member = TestTenants.login(mvc, members.create(ws.tenantId(), Set.of(reader)));
        search(member).andExpect(status().isForbidden());
    }
}
