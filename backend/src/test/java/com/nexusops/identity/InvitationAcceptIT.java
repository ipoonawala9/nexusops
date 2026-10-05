package com.nexusops.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Session;
import com.nexusops.support.TestTenants.Workspace;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class InvitationAcceptIT extends IntegrationTestSupport {

    static final String INVALID = "This invitation link is invalid or has expired.";

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    Workspace ws;
    Session owner;
    String email;
    String token;

    @BeforeEach
    void invite() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("acc"));
        owner = TestTenants.login(mvc, ws);
        UUID support = TestRoles.create(mvc, owner, "Support", "identity.user.read", "tenant.settings.read");
        email = "joiner-" + UUID.randomUUID().toString().substring(0, 6) + "@x.test";
        mvc.perform(post("/api/v1/invitations").header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"roleId\":\"" + support + "\"}"))
                .andExpect(status().isCreated());
        token = mail.lastTokenFor(email);
    }

    private ResultActions accept(String t, String password) throws Exception {
        return mvc.perform(post("/api/v1/invitations/accept").contentType(MediaType.APPLICATION_JSON).content("""
                {"token":"%s","firstName":"Jo","lastName":"Iner","password":"%s"}""".formatted(t, password)));
    }

    @Test
    void previewIsPublicAndShowsWhatIsBeingAccepted() throws Exception {
        mvc.perform(get("/api/v1/invitations/preview").param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspace").value(ws.slug()))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.roleName").value("Support"));
    }

    @Test
    void acceptCreatesAVerifiedMemberWhoCanLogInWithTheInvitedRole() throws Exception {
        accept(token, TestTenants.PASSWORD).andExpect(status().isCreated())
                .andExpect(jsonPath("$.workspace").value(ws.slug()))
                .andExpect(jsonPath("$.email").value(email));
        Session member = TestTenants.login(mvc, new Workspace(ws.tenantId(), ws.slug(), email, TestTenants.PASSWORD));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + member.accessToken()))
                .andExpect(jsonPath("$.permissions", Matchers.containsInAnyOrder("identity.user.read", "tenant.settings.read")));
        mvc.perform(get("/api/v1/invitations").header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(jsonPath("$[0].status").value("ACCEPTED"));
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForList("select action from audit_events", String.class))
                .contains("InvitationAccepted", "UserRegistered");
        accept(token, TestTenants.PASSWORD).andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(INVALID));
    }

    @Test
    void invalidTokensAreRejectedUniformly() throws Exception {
        accept("garbage", TestTenants.PASSWORD).andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(INVALID));
        accept(UUID.randomUUID() + token.substring(36), TestTenants.PASSWORD).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/invitations/preview").param("token", "garbage")).andExpect(status().isBadRequest());
        OwnerJdbc.ownerAs(ws.tenantId()).update("update invitations set expires_at = now() - interval '1 minute'");
        accept(token, TestTenants.PASSWORD).andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(INVALID));
    }

    @Test
    void weakPasswordIsAFieldErrorAndLeavesTheInvitationPending() throws Exception {
        accept(token, "Password1234").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
        accept(token, TestTenants.PASSWORD).andExpect(status().isCreated());
    }

    @Test
    void suspendedWorkspacesCannotBeJoined() throws Exception {
        OwnerJdbc.jdbc().update("update tenants set status = 'SUSPENDED' where id = ?", ws.tenantId());
        accept(token, TestTenants.PASSWORD).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Workspace suspended."));
    }

    @Test
    void concurrentAcceptCreatesExactlyOneUser() throws Exception {
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return accept(token, TestTenants.PASSWORD).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(statuses).containsExactlyInAnyOrder(201, 400);
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from users where email = ?", Long.class, email)).isOne();
    }
}
