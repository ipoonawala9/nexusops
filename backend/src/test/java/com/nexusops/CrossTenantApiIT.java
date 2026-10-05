package com.nexusops;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Tenant A's owner (full permissions) probes every Tenant B resource id: always 404, never 403 or 200. */
@AutoConfigureMockMvc
class CrossTenantApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Session ownerA;
    Session ownerB;
    Workspace b;
    UUID roleB;
    UUID userB;
    UUID invitationB;

    @BeforeEach
    void twoTenants() throws Exception {
        Workspace a = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("xa"));
        b = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("xb"));
        ownerA = TestTenants.login(mvc, a);
        ownerB = TestTenants.login(mvc, b);
        roleB = TestRoles.create(mvc, ownerB, "SecretB", "identity.user.read");
        String memberEmail = members.create(b.tenantId(), Set.of(roleB)).email();
        userB = OwnerJdbc.ownerAs(b.tenantId()).queryForObject("select id from users where email = ?", UUID.class, memberEmail);
        String body = as(ownerB, post("/api/v1/invitations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"invitee-b@x.test\",\"roleId\":\"" + roleB + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        invitationB = UUID.fromString(JsonPath.read(body, "$.id"));
        as(ownerB, put("/api/v1/tenant/modules/CRM").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk());
    }

    private ResultActions as(Session s, MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b.header("Authorization", "Bearer " + s.accessToken()));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void everyIdBearingEndpointReturns404ForAnotherTenantsIds() throws Exception {
        as(ownerA, get("/api/v1/users/" + userB)).andExpect(status().isNotFound());
        as(ownerA, json(patch("/api/v1/users/" + userB), "{\"firstName\":\"Hacked\"}")).andExpect(status().isNotFound());
        as(ownerA, json(patch("/api/v1/users/" + userB), "{\"status\":\"DISABLED\"}")).andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/users/" + userB + "/roles"), "{\"roleIds\":[]}")).andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/roles/" + roleB)).andExpect(status().isNotFound());
        as(ownerA, json(patch("/api/v1/roles/" + roleB), "{\"name\":\"Hacked\"}")).andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/roles/" + roleB + "/permissions"), "{\"permissions\":[]}")).andExpect(status().isNotFound());
        as(ownerA, delete("/api/v1/roles/" + roleB)).andExpect(status().isNotFound());
        as(ownerA, delete("/api/v1/invitations/" + invitationB)).andExpect(status().isNotFound());

        as(ownerB, get("/api/v1/users/" + userB)).andExpect(status().isOk()).andExpect(jsonPath("$.firstName").value("Mem"));
        as(ownerB, get("/api/v1/roles/" + roleB)).andExpect(jsonPath("$.name").value("SecretB"));
    }

    @Test
    void anotherTenantsRoleCannotBeGrantedOrInvitedWith() throws Exception {
        UUID ownUser = UUID.fromString(JsonPath.read(
                as(ownerA, get("/api/v1/users")).andReturn().getResponse().getContentAsString(), "$.items[0].id"));
        as(ownerA, json(put("/api/v1/users/" + ownUser + "/roles"), "{\"roleIds\":[\"" + roleB + "\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("roleIds"));
        as(ownerA, json(post("/api/v1/invitations"), "{\"email\":\"x@x.test\",\"roleId\":\"" + roleB + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("roleIds"));
    }

    @Test
    void listsNeverContainAnotherTenantsRows() throws Exception {
        as(ownerA, get("/api/v1/users")).andExpect(jsonPath("$.items[*].id", Matchers.not(Matchers.hasItem(userB.toString()))))
                .andExpect(jsonPath("$.total").value(1));
        as(ownerA, get("/api/v1/roles")).andExpect(jsonPath("$[*].id", Matchers.not(Matchers.hasItem(roleB.toString()))));
        as(ownerA, get("/api/v1/invitations")).andExpect(jsonPath("$", Matchers.empty()));
        as(ownerA, get("/api/v1/audit-events").param("size", "100"))
                .andExpect(jsonPath("$.items[*].entityId", Matchers.not(Matchers.hasItem(roleB.toString()))))
                .andExpect(jsonPath("$.items[*].entityId", Matchers.not(Matchers.hasItem(invitationB.toString()))));
        as(ownerA, get("/api/v1/tenant/modules")).andExpect(jsonPath("$[?(@.code == 'CRM')].enabled", Matchers.contains(false)));
        as(ownerA, get("/api/v1/me")).andExpect(jsonPath("$.modules", Matchers.empty()));
    }
}
