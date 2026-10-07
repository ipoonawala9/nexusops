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
    Workspace a;
    Workspace b;
    UUID roleB;
    UUID userB;
    UUID invitationB;
    UUID orgB, personB, productB, taskB, documentB, userBId;

    @BeforeEach
    void twoTenants() throws Exception {
        a = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("xa"));
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

        com.nexusops.support.Api apiB = new com.nexusops.support.Api(mvc, ownerB);
        orgB = com.nexusops.support.Api.id(apiB.post("/api/v1/organizations", "{\"name\":\"Beta Corp\"}"));
        personB = com.nexusops.support.Api.id(apiB.post("/api/v1/persons",
                "{\"firstName\":\"Bea\",\"organizationId\":\"" + orgB + "\"}"));
        productB = com.nexusops.support.Api.id(apiB.post("/api/v1/products", "{\"sku\":\"B-1\",\"name\":\"Beta widget\"}"));
        apiB.post("/api/v1/activities", "{\"subjectType\":\"PARTY\",\"subjectId\":\"" + orgB
                + "\",\"type\":\"NOTE\",\"summary\":\"B secret\"}").andExpect(status().isCreated());
        taskB = com.nexusops.support.Api.id(apiB.post("/api/v1/tasks", "{\"title\":\"B task\",\"subjectType\":\"PARTY\","
                + "\"subjectId\":\"" + orgB + "\"}"));
        documentB = com.nexusops.support.Api.id(apiB.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/documents")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "b.txt", "text/plain",
                                "B".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .param("subjectType", "PARTY").param("subjectId", orgB.toString())));
        userBId = OwnerJdbc.ownerAs(b.tenantId()).queryForObject("select id from users where email = ?", UUID.class,
                b.email());
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

        // the probes must have left tenant B completely untouched
        as(ownerB, get("/api/v1/users/" + userB)).andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Mem"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.roles[*].id", Matchers.hasItem(roleB.toString())));
        as(ownerB, get("/api/v1/roles/" + roleB)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("SecretB"))
                .andExpect(jsonPath("$.permissions", Matchers.contains("identity.user.read")));
        as(ownerB, get("/api/v1/invitations"))
                .andExpect(jsonPath("$[?(@.id == '" + invitationB + "')].status", Matchers.contains("PENDING")));
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
        // positive controls: the audit list has the right shape, so the negative assertions below are meaningful
        as(ownerA, get("/api/v1/audit-events").param("size", "100"))
                .andExpect(jsonPath("$.items", Matchers.not(Matchers.empty())))
                .andExpect(jsonPath("$.items[*].action", Matchers.hasItem("TenantCreated")))
                .andExpect(jsonPath("$.items[*].action", Matchers.hasItem("LoginSucceeded")));
        as(ownerB, get("/api/v1/audit-events").param("size", "100"))
                .andExpect(jsonPath("$.items[*].entityId", Matchers.hasItem(roleB.toString())))
                .andExpect(jsonPath("$.items[*].entityId", Matchers.hasItem(invitationB.toString())));
        as(ownerA, get("/api/v1/audit-events").param("size", "100"))
                .andExpect(jsonPath("$.items[*].entityId", Matchers.not(Matchers.hasItem(roleB.toString()))))
                .andExpect(jsonPath("$.items[*].entityId", Matchers.not(Matchers.hasItem(invitationB.toString()))));
        as(ownerA, get("/api/v1/tenant/modules")).andExpect(jsonPath("$[?(@.code == 'CRM')].enabled", Matchers.contains(false)));
        as(ownerA, get("/api/v1/me")).andExpect(jsonPath("$.tenant.slug").value(a.slug()))
                .andExpect(jsonPath("$.modules", Matchers.empty()));
    }

    @Test
    void anotherTenantsModuleToggleDoesNotLeak() throws Exception {
        as(ownerA, put("/api/v1/tenant/modules/HRMS").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk());
        as(ownerB, get("/api/v1/tenant/modules"))
                .andExpect(jsonPath("$[?(@.code == 'HRMS')].enabled", Matchers.contains(false)))
                .andExpect(jsonPath("$[?(@.code == 'CRM')].enabled", Matchers.contains(true)));
    }

    @Test
    void invitationTokenWithSwappedTenantPrefixIsRejected() throws Exception {
        String token = mail.lastTokenFor("invitee-b@x.test");
        String forged = a.tenantId() + token.substring(36);
        String invalid = "This invitation link is invalid or has expired.";
        mvc.perform(get("/api/v1/invitations/preview").param("token", forged))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(invalid));
        mvc.perform(post("/api/v1/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + forged + "\",\"firstName\":\"X\",\"lastName\":\"Y\",\"password\":\"" + TestTenants.PASSWORD + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(invalid));
        // positive control: the genuine token previews B's workspace
        mvc.perform(get("/api/v1/invitations/preview").param("token", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.workspace").value(b.slug()));
    }

    @Test
    void canonicalRecordsOfAnotherTenantAreInvisibleAndUntouchable() throws Exception {
        as(ownerA, get("/api/v1/parties/" + orgB)).andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/parties/" + personB)).andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/organizations/" + orgB), "{\"name\":\"Hacked\",\"version\":0}")).andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/persons/" + personB), "{\"firstName\":\"Hacked\",\"version\":0}")).andExpect(status().isNotFound());
        as(ownerA, post("/api/v1/parties/" + orgB + "/archive")).andExpect(status().isNotFound());
        as(ownerA, post("/api/v1/parties/" + orgB + "/restore")).andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/parties/" + orgB + "/roles/CUSTOMER"), "{}")).andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/products/" + productB)).andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/products/" + productB), "{\"sku\":\"H\",\"name\":\"H\",\"version\":0}")).andExpect(status().isNotFound());
        as(ownerA, post("/api/v1/products/" + productB + "/archive")).andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/activities").param("subjectType", "PARTY").param("subjectId", orgB.toString()))
                .andExpect(status().isNotFound());
        as(ownerA, json(post("/api/v1/activities"), "{\"subjectType\":\"PARTY\",\"subjectId\":\"" + orgB
                + "\",\"type\":\"NOTE\",\"summary\":\"x\"}")).andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/tasks/" + taskB)).andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/tasks/" + taskB), "{\"title\":\"Hacked\",\"version\":0}")).andExpect(status().isNotFound());
        as(ownerA, json(post("/api/v1/tasks/" + taskB + "/status"), "{\"status\":\"DONE\"}")).andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/documents").param("subjectType", "PARTY").param("subjectId", orgB.toString()))
                .andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/documents/" + documentB + "/content")).andExpect(status().isNotFound());
        as(ownerA, delete("/api/v1/documents/" + documentB)).andExpect(status().isNotFound());

        // references to another tenant's rows are refused too
        as(ownerA, json(post("/api/v1/persons"), "{\"firstName\":\"X\",\"organizationId\":\"" + orgB + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("organizationId"));
        as(ownerA, json(post("/api/v1/tasks"), "{\"title\":\"X\",\"assigneeId\":\"" + userBId + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("assigneeId"));
        as(ownerA, json(post("/api/v1/tasks"), "{\"title\":\"X\",\"subjectType\":\"PARTY\",\"subjectId\":\"" + orgB + "\"}"))
                .andExpect(status().isNotFound());

        // tenant B is untouched
        as(ownerB, get("/api/v1/parties/" + orgB)).andExpect(jsonPath("$.name").value("Beta Corp"))
                .andExpect(jsonPath("$.archivedAt").doesNotExist()).andExpect(jsonPath("$.roles", Matchers.empty()));
        as(ownerB, get("/api/v1/tasks/" + taskB)).andExpect(jsonPath("$.title").value("B task"))
                .andExpect(jsonPath("$.status").value("OPEN"));
        as(ownerB, get("/api/v1/documents/" + documentB + "/content")).andExpect(status().isOk());
    }

    @Test
    void canonicalListsNeverContainAnotherTenantsRows() throws Exception {
        as(ownerA, get("/api/v1/parties")).andExpect(jsonPath("$.total").value(0));
        as(ownerA, get("/api/v1/parties").param("q", "Beta")).andExpect(jsonPath("$.total").value(0));
        as(ownerA, get("/api/v1/products")).andExpect(jsonPath("$.total").value(0));
        as(ownerA, get("/api/v1/tasks")).andExpect(jsonPath("$.total").value(0));
        as(ownerA, get("/api/v1/tasks/assignees")).andExpect(jsonPath("$[*].id", Matchers.not(Matchers.hasItem(userBId.toString()))));
        // positive control
        as(ownerB, get("/api/v1/parties")).andExpect(jsonPath("$.total").value(2));
    }
}
