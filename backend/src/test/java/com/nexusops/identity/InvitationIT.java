package com.nexusops.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Session;
import com.nexusops.support.TestTenants.Workspace;
import com.nexusops.identity.domain.InvitationRepository;
import com.nexusops.shared.TenantContext;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
class InvitationIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;
    @Autowired InvitationRepository invitationRepo;
    @Autowired TransactionTemplate tx;

    Workspace ws;
    Session owner;
    UUID support;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("inv"));
        owner = TestTenants.login(mvc, ws);
        support = TestRoles.create(mvc, owner, "Support", "identity.user.read", "tenant.settings.read");
    }

    private ResultActions invite(Session s, String email, UUID roleId) throws Exception {
        return mvc.perform(post("/api/v1/invitations").header("Authorization", "Bearer " + s.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"roleId\":\"" + roleId + "\"}"));
    }

    private UUID idOf(ResultActions result) throws Exception {
        return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id"));
    }

    @Test
    void ownerInvitesAMemberAndAnEmailIsSent() throws Exception {
        invite(owner, " New@" + ws.slug() + ".TEST ", support)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("new@" + ws.slug() + ".test"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.roleName").value("Support"));
        assertThat(mail.sentTo("new@" + ws.slug() + ".test")).singleElement()
                .satisfies(m -> assertThat(m.textBody()).contains("/invite/accept?token="));
        mvc.perform(get("/api/v1/invitations").header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].email", Matchers.hasItem("new@" + ws.slug() + ".test")));
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select token_hash from invitations", String.class)).matches("[0-9a-f]{64}");
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForList("select action from audit_events", String.class))
                .contains("InvitationCreated");
    }

    @Test
    void duplicatesAndExistingMembersAreRejected() throws Exception {
        invite(owner, "dup@x.test", support).andExpect(status().isCreated());
        invite(owner, "DUP@x.test", support).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.detail").value("An invitation is already pending for this email."));
        invite(owner, ws.email(), support).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This person is already a member of the workspace."));
        invite(owner, "x@x.test", UUID.randomUUID()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("roleIds"));
    }

    @Test
    void revokeAndReinvite() throws Exception {
        UUID id = idOf(invite(owner, "rev@x.test", support));
        mvc.perform(delete("/api/v1/invitations/" + id).header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/invitations/" + id).header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This invitation is no longer pending."));
        mvc.perform(delete("/api/v1/invitations/" + UUID.randomUUID()).header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isNotFound());
        invite(owner, "rev@x.test", support).andExpect(status().isCreated());
    }

    @Test
    void expiredInvitationsAreSupersededOnReinvite() throws Exception {
        invite(owner, "late@x.test", support).andExpect(status().isCreated());
        OwnerJdbc.ownerAs(ws.tenantId()).update("update invitations set expires_at = now() - interval '1 minute'");
        invite(owner, "late@x.test", support).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/invitations").header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(jsonPath("$[?(@.email == 'late@x.test')].status", Matchers.containsInAnyOrder("PENDING", "REVOKED")));
    }

    @Test
    void freePlanSeatLimitCountsActiveUsersAndPendingInvitations() throws Exception {
        invite(owner, "a@x.test", support).andExpect(status().isCreated());
        UUID second = idOf(invite(owner, "b@x.test", support).andExpect(status().isCreated()));
        invite(owner, "c@x.test", support).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Your plan allows 3 users. Upgrade to add more."));
        mvc.perform(delete("/api/v1/invitations/" + second).header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isNoContent());
        invite(owner, "c@x.test", support).andExpect(status().isCreated());
    }

    @Test
    void revokeWaitsForARowLockAndThenSeesTheCommittedState() throws Exception {
        UUID id = idOf(invite(owner, "lock@x.test", support));
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        // Stands in for a concurrent accept: locks the row, changes it, commits only when released.
        var holder = pool.submit(() -> TenantContext.runAs(ws.tenantId(), () -> tx.executeWithoutResult(s -> {
            invitationRepo.findForUpdateById(id).orElseThrow().revoke(Instant.now());
            holding.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        })));
        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
        var revoke = pool.submit(() -> mvc.perform(delete("/api/v1/invitations/" + id)
                .header("Authorization", "Bearer " + owner.accessToken())).andReturn().getResponse().getStatus());
        Thread.sleep(500);
        assertThat(revoke.isDone()).as("revoke must block on the row lock").isFalse();
        release.countDown();
        holder.get(5, TimeUnit.SECONDS);
        assertThat(revoke.get(10, TimeUnit.SECONDS)).as("revoke re-reads the committed state").isEqualTo(409);
        pool.shutdown();
    }

    @Test
    void invitersCannotEscalate() throws Exception {
        UUID inviterRole = TestRoles.create(mvc, owner, "Inviter", "identity.user.invite", "identity.user.read");
        Session inviter = TestTenants.login(mvc, members.create(ws.tenantId(), Set.of(inviterRole)));
        invite(inviter, "esc@x.test", support).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("You can't grant permissions you don't have."));
        invite(inviter, "own@x.test", TestRoles.system(ws.tenantId(), "TENANT_OWNER")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Only workspace owners can manage the owner role."));
        UUID readers = TestRoles.create(mvc, owner, "Readers", "identity.user.read");
        invite(inviter, "ok@x.test", readers).andExpect(status().isCreated());
        invite(owner, "co-owner@x.test", TestRoles.system(ws.tenantId(), "TENANT_OWNER")).andExpect(status().isConflict());
    }
}
