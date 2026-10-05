package com.nexusops.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Session;
import com.nexusops.support.TestTenants.Workspace;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class UserAdminIT extends IntegrationTestSupport {

    static final String LAST_OWNER = "A workspace needs at least one active owner.";
    static final String OWNER_ONLY = "Only workspace owners can manage the owner role.";
    static final String HIERARCHY = "You can't manage a user with permissions you don't have.";

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Session owner;
    UUID ownerId;
    UUID ownerRole;
    UUID support;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("users"));
        OwnerJdbc.jdbc().update("update tenants set plan_code = 'BUSINESS' where id = ?", ws.tenantId());
        owner = TestTenants.login(mvc, ws);
        ownerId = userId(ws.email());
        ownerRole = TestRoles.system(ws.tenantId(), "TENANT_OWNER");
        support = TestRoles.create(mvc, owner, "Support", "identity.user.read", "tenant.settings.read");
    }

    private UUID userId(String email) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select id from users where email = ?", UUID.class, email);
    }

    private ResultActions as(Session s, MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b.header("Authorization", "Bearer " + s.accessToken()));
    }

    private ResultActions setStatus(Session s, UUID id, String status) throws Exception {
        return as(s, patch("/api/v1/users/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\"}"));
    }

    private ResultActions setRoles(Session s, UUID id, UUID... roleIds) throws Exception {
        StringBuilder json = new StringBuilder("{\"roleIds\":[");
        for (int i = 0; i < roleIds.length; i++) {
            json.append(i == 0 ? "" : ",").append('"').append(roleIds[i]).append('"');
        }
        return as(s, put("/api/v1/users/" + id + "/roles").contentType(MediaType.APPLICATION_JSON)
                .content(json.append("]}").toString()));
    }

    @Test
    void listsWithPagingAndFilters() throws Exception {
        members.create(ws.tenantId(), Set.of(support));
        members.create(ws.tenantId(), Set.of(support));
        as(owner, get("/api/v1/users").param("size", "2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.size").value(2));
        as(owner, get("/api/v1/users").param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.items.length()").value(1));
        as(owner, get("/api/v1/users").param("q", "MEMBER-")).andExpect(jsonPath("$.total").value(2));
        as(owner, get("/api/v1/users").param("status", "ACTIVE")).andExpect(jsonPath("$.total").value(3));
        as(owner, get("/api/v1/users").param("size", "101")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));
        as(owner, get("/api/v1/users").param("status", "BOGUS")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("status"));
    }

    @Test
    void getsAndRenamesAUser() throws Exception {
        UUID member = userId(members.create(ws.tenantId(), Set.of(support)).email());
        as(owner, get("/api/v1/users/" + member)).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0].name").value("Support"))
                .andExpect(jsonPath("$.emailVerified").value(true));
        as(owner, patch("/api/v1/users/" + member).contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\" Grace \",\"lastName\":\"Hopper\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.firstName").value("Grace"));
        as(owner, get("/api/v1/users/" + UUID.randomUUID())).andExpect(status().isNotFound());
        as(owner, put("/api/v1/users/" + member + "/roles").contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleIds\":[null]}")).andExpect(status().isBadRequest());
    }

    @Test
    void disablingKillsLiveSessions() throws Exception {
        Workspace memberWs = members.create(ws.tenantId(), Set.of(support));
        Session member = TestTenants.login(mvc, memberWs);
        UUID memberId = userId(memberWs.email());
        as(member, get("/api/v1/me")).andExpect(status().isOk());

        setStatus(owner, memberId, "DISABLED").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));

        as(member, get("/api/v1/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("nexus_rt", member.refreshToken())))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"workspace":"%s","email":"%s","password":"%s"}""".formatted(ws.slug(), memberWs.email(), memberWs.password())))
                .andExpect(status().isUnauthorized());

        setStatus(owner, memberId, "ACTIVE").andExpect(status().isOk());
        TestTenants.login(mvc, memberWs);
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForList("select action from audit_events", String.class))
                .contains("UserDisabled", "UserEnabled");
    }

    @Test
    void ownersCannotLockThemselvesOrTheWorkspaceOut() throws Exception {
        setStatus(owner, ownerId, "DISABLED").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("You can't disable your own account."));
        setRoles(owner, ownerId, support).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(LAST_OWNER));

        UUID coOwner = userId(members.create(ws.tenantId(), Set.of(support)).email());
        setRoles(owner, coOwner, ownerRole).andExpect(status().isOk());
        setRoles(owner, ownerId, support).andExpect(status().isOk()); // another owner remains
    }

    @Test
    void concurrentMutualDemotionKeepsAnOwner() throws Exception {
        Workspace secondWs = members.create(ws.tenantId(), Set.of(ownerRole));
        Session second = TestTenants.login(mvc, secondWs);
        UUID secondId = userId(secondWs.email());

        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> results = new ArrayList<>();
        results.add(pool.submit(() -> { start.await(); return setRoles(owner, secondId, support).andReturn().getResponse().getStatus(); }));
        results.add(pool.submit(() -> { start.await(); return setRoles(second, ownerId, support).andReturn().getResponse().getStatus(); }));
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) statuses.add(f.get(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("""
                select count(*) from users u join user_roles ur on ur.user_id = u.id
                where ur.role_id = ? and u.status = 'ACTIVE'""", Long.class, ownerRole)).isOne();
    }

    @Test
    void escalationGuardsOnAssignmentAndDisable() throws Exception {
        UUID assigner = TestRoles.create(mvc, owner, "Assigner", "authorization.role.assign", "identity.user.read",
                "identity.user.disable");
        Session member = TestTenants.login(mvc, members.create(ws.tenantId(), Set.of(assigner)));
        UUID target = userId(members.create(ws.tenantId(), Set.of()).email());
        setRoles(member, target, support).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("You can't grant permissions you don't have."));
        setRoles(member, target, ownerRole).andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value(OWNER_ONLY));
        setStatus(member, ownerId, "DISABLED").andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value(OWNER_ONLY));
        as(member, patch("/api/v1/users/" + target).contentType(MediaType.APPLICATION_JSON).content("{\"firstName\":\"X\"}"))
                .andExpect(status().isForbidden()); // lacks identity.user.update
    }

    @Test
    void cannotDisableEnableOrReassignAMorePrivilegedUser() throws Exception {
        UUID disabler = TestRoles.create(mvc, owner, "Disabler", "identity.user.disable", "identity.user.read",
                "authorization.role.assign");
        UUID manager = TestRoles.create(mvc, owner, "Manager", "identity.user.disable", "identity.user.read",
                "authorization.role.assign", "authorization.role.manage");
        UUID reader = TestRoles.create(mvc, owner, "Reader", "identity.user.read");
        Session member = TestTenants.login(mvc, members.create(ws.tenantId(), Set.of(disabler)));
        UUID stronger = userId(members.create(ws.tenantId(), Set.of(manager)).email());

        setStatus(member, stronger, "DISABLED").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(HIERARCHY));
        setRoles(member, stronger).andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value(HIERARCHY));
        setStatus(owner, stronger, "DISABLED").andExpect(status().isOk());
        setStatus(member, stronger, "ACTIVE").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(HIERARCHY));
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select status from users where id = ?", String.class, stronger)).isEqualTo("DISABLED");
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from user_roles where user_id = ?", Long.class, stronger)).isOne();

        // Positive controls: a weaker user and a peer with the same permissions are manageable.
        UUID weaker = userId(members.create(ws.tenantId(), Set.of(reader)).email());
        setStatus(member, weaker, "DISABLED").andExpect(status().isOk());
        setStatus(member, weaker, "ACTIVE").andExpect(status().isOk());
        setRoles(member, weaker).andExpect(status().isOk());
        UUID peer = userId(members.create(ws.tenantId(), Set.of(disabler)).email());
        setStatus(member, peer, "DISABLED").andExpect(status().isOk());
    }

    @Test
    void onlyOwnersCanReEnableAnOwner() throws Exception {
        UUID coOwner = userId(members.create(ws.tenantId(), Set.of(ownerRole)).email());
        setStatus(owner, coOwner, "DISABLED").andExpect(status().isOk());
        UUID disabler = TestRoles.create(mvc, owner, "Disabler", "identity.user.disable", "identity.user.read");
        Session member = TestTenants.login(mvc, members.create(ws.tenantId(), Set.of(disabler)));
        setStatus(member, coOwner, "ACTIVE").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(OWNER_ONLY));
        setStatus(owner, coOwner, "ACTIVE").andExpect(status().isOk());
    }

    @Test
    void enablingRespectsTheSeatLimit() throws Exception {
        OwnerJdbc.jdbc().update("update tenants set plan_code = 'FREE' where id = ?", ws.tenantId());
        members.create(ws.tenantId(), Set.of(support));
        UUID third = userId(members.create(ws.tenantId(), Set.of(support)).email());
        setStatus(owner, third, "DISABLED").andExpect(status().isOk());
        as(owner, post("/api/v1/invitations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"seat@x.test\",\"roleId\":\"" + support + "\"}")).andExpect(status().isCreated());
        setStatus(owner, third, "ACTIVE").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Your plan allows 3 users. Upgrade to add more."));
    }
}
