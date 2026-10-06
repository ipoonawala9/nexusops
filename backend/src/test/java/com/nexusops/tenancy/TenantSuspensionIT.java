package com.nexusops.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.shared.TenantContext;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Session;
import com.nexusops.support.TestTenants.Workspace;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class TenantSuspensionIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TenantDirectory directory;

    private ResultActions me(Session session) throws Exception {
        return mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + session.accessToken()));
    }

    private void suspend(UUID tenantId, UUID operator, String reason) {
        try (var scope = TenantContext.open(tenantId, null)) {
            directory.suspendCurrent(operator, reason);
        }
    }

    private void reactivate(UUID tenantId, UUID operator, String reason) {
        try (var scope = TenantContext.open(tenantId, null)) {
            directory.reactivateCurrent(operator, reason);
        }
    }

    private static void assertProblem(ThrowingCallable call, HttpStatus status, String detail) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiProblem.class, p -> {
            assertThat(p.status()).isEqualTo(status);
            assertThat(p.getMessage()).isEqualTo(detail);
        });
    }

    @Test
    void suspensionBlocksMembersOnTheirNextRequestAndReactivationRestoresThem() throws Exception {
        Workspace a = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("susp-a"));
        Workspace b = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("susp-b"));
        Session sa = TestTenants.login(mvc, a);
        Session sb = TestTenants.login(mvc, b);
        me(sa).andExpect(status().isOk()); // warms the principal cache: only eviction can make the next call 403

        UUID operator = UUID.randomUUID();
        suspend(a.tenantId(), operator, "  Chargeback investigation  ");

        me(sa).andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value("Workspace suspended."));
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("nexus_rt", sa.refreshToken())))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"workspace":"%s","email":"%s","password":"%s"}""".formatted(a.slug(), a.email(), a.password())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value("Workspace suspended."));
        me(sb).andExpect(status().isOk());

        Map<String, Object> audit = OwnerJdbc.ownerAs(a.tenantId()).queryForMap("""
                select actor_type, actor_id, metadata->>'reason' as reason from audit_events
                where action = 'TenantSuspended'""");
        assertThat(audit.get("actor_type")).isEqualTo("PLATFORM");
        assertThat(audit.get("actor_id")).isEqualTo(operator);
        assertThat(audit.get("reason")).isEqualTo("Chargeback investigation");

        reactivate(a.tenantId(), operator, "Resolved");
        me(sa).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("nexus_rt", sa.refreshToken())))
                .andExpect(status().isOk());
        assertThat(OwnerJdbc.ownerAs(a.tenantId()).queryForObject(
                "select count(*) from audit_events where action = 'TenantReactivated'", Long.class)).isOne();
    }

    @Test
    void onlyLegalTransitionsAreAllowed() throws Exception {
        Workspace active = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("susp-c"));
        Workspace pending = TestTenants.signup(mvc, TestTenants.uniqueSlug("susp-d"));
        UUID operator = UUID.randomUUID();

        assertProblem(() -> reactivate(active.tenantId(), operator, "x"), HttpStatus.CONFLICT,
                "Only a suspended workspace can be reactivated.");
        assertProblem(() -> suspend(pending.tenantId(), operator, "x"), HttpStatus.CONFLICT,
                "Only an active workspace can be suspended.");
        suspend(active.tenantId(), operator, "x".repeat(500));
        assertProblem(() -> suspend(active.tenantId(), operator, "again"), HttpStatus.CONFLICT,
                "Only an active workspace can be suspended.");
        assertProblem(() -> suspend(UUID.randomUUID(), operator, "x"), HttpStatus.NOT_FOUND, "Workspace not found.");

        for (String bad : new String[] {null, "   ", "x".repeat(501)}) {
            assertThatThrownBy(() -> reactivate(active.tenantId(), operator, bad))
                    .isInstanceOfSatisfying(ApiProblem.class, p -> {
                        assertThat(p.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(p.errors()).extracting(ApiProblem.FieldError::field).containsExactly("reason");
                    });
        }
    }
}
