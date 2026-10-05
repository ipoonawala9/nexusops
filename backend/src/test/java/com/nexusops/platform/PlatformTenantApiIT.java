package com.nexusops.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.platform.application.PlatformUserAdmin;
import com.nexusops.platform.domain.PlatformRole;
import com.nexusops.platform.security.PlatformTokenService;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestPlatformUsers;
import com.nexusops.support.TestPlatformUsers.Operator;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class PlatformTenantApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired PlatformUserAdmin admin;
    @Autowired PlatformTokenService platformTokens;
    @Autowired TestMembers members;

    Workspace a;
    Workspace b;
    Operator adminOp;
    String adminToken;
    String supportToken;

    @BeforeEach
    void setUp() throws Exception {
        a = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("pt-a"));
        b = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("pt-b"));
        members.create(a.tenantId(), Set.of());
        adminOp = TestPlatformUsers.create(admin, PlatformRole.PLATFORM_ADMIN);
        adminToken = platformTokens.issue(adminOp.id(), 0).value();
        supportToken = platformTokens.issue(TestPlatformUsers.create(admin, PlatformRole.PLATFORM_SUPPORT).id(), 0).value();
    }

    private ResultActions as(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token));
    }

    private ResultActions change(String token, UUID tenantId, String action, String reason) throws Exception {
        return as(token, post("/api/v1/platform/tenants/" + tenantId + "/" + action)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + reason + "\"}"));
    }

    @Test
    void listsWorkspacesWithActiveUserCountsAndOwners() throws Exception {
        as(supportToken, get("/api/v1/platform/tenants").param("q", a.slug().toUpperCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(a.tenantId().toString()))
                .andExpect(jsonPath("$.items[0].slug").value(a.slug()))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].planCode").value("FREE"))
                .andExpect(jsonPath("$.items[0].activeUsers").value(2))
                .andExpect(jsonPath("$.items[0].ownerEmails[0]").value(a.email()))
                .andExpect(jsonPath("$.items[0].ownerEmails.length()").value(1));
        as(supportToken, get("/api/v1/platform/tenants").param("q", a.slug()).param("status", "SUSPENDED"))
                .andExpect(jsonPath("$.total").value(0));
        as(supportToken, get("/api/v1/platform/tenants").param("q", "%")).andExpect(jsonPath("$.total").value(0));
        as(supportToken, get("/api/v1/platform/tenants").param("size", "2"))
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.size").value(2));
        as(supportToken, get("/api/v1/platform/tenants").param("status", "BOGUS"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("status"));
        as(supportToken, get("/api/v1/platform/tenants").param("size", "101"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("size"));
        as(supportToken, get("/api/v1/platform/tenants").param("page", "abc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("page"));
    }

    @Test
    void adminSuspendsAndReactivatesAWorkspace() throws Exception {
        var sa = TestTenants.login(mvc, a);
        var sb = TestTenants.login(mvc, b);

        change(adminToken, a.tenantId(), "suspend", "Abuse report 42").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED")).andExpect(jsonPath("$.slug").value(a.slug()));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + sa.accessToken()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value("Workspace suspended."));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + sb.accessToken())).andExpect(status().isOk());

        Map<String, Object> audit = OwnerJdbc.ownerAs(a.tenantId()).queryForMap("""
                select actor_type, actor_id, metadata->>'reason' as reason from audit_events where action = 'TenantSuspended'""");
        assertThat(audit).containsEntry("actor_type", "PLATFORM").containsEntry("actor_id", adminOp.id())
                .containsEntry("reason", "Abuse report 42");

        change(adminToken, a.tenantId(), "reactivate", "Resolved").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + sa.accessToken())).andExpect(status().isOk());
    }

    @Test
    void supportCannotSuspendAndTenantTokensCannotReachThePlatform() throws Exception {
        change(supportToken, a.tenantId(), "suspend", "nope").andExpect(status().isForbidden());
        String tenantToken = TestTenants.login(mvc, a).accessToken();
        as(tenantToken, get("/api/v1/platform/tenants")).andExpect(status().isUnauthorized());
        assertThat(OwnerJdbc.jdbc().queryForObject("select status from tenants where id = ?", String.class, a.tenantId()))
                .isEqualTo("ACTIVE");
    }

    @Test
    void validatesIdsReasonsAndTransitions() throws Exception {
        change(adminToken, UUID.randomUUID(), "suspend", "x")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Workspace not found."));
        as(adminToken, post("/api/v1/platform/tenants/not-a-uuid/suspend").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"x\"}")).andExpect(status().isNotFound());
        change(adminToken, a.tenantId(), "suspend", "   ")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reason"));
        change(adminToken, a.tenantId(), "reactivate", "x")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Only a suspended workspace can be reactivated."));
    }

    @Test
    void platformListingDoesNotLeakIntoTenantRequests() throws Exception {
        String ownerToken = TestTenants.login(mvc, a).accessToken();
        for (int i = 0; i < 5; i++) {
            as(supportToken, get("/api/v1/platform/tenants")).andExpect(status().isOk());
            String users = as(ownerToken, get("/api/v1/users")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(2))
                    .andReturn().getResponse().getContentAsString();
            assertThat(users).doesNotContain(b.slug());
        }
    }
}
