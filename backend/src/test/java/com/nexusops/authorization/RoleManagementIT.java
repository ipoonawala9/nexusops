package com.nexusops.authorization;

import static org.assertj.core.api.Assertions.assertThat;
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

@AutoConfigureMockMvc
class RoleManagementIT extends IntegrationTestSupport {

    static final String ESCALATION = "You can't grant permissions you don't have.";
    static final String SYSTEM = "System roles can't be changed.";

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Session owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("roles"));
        owner = TestTenants.login(mvc, ws);
    }

    private ResultActions as(Session s, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b)
            throws Exception {
        return mvc.perform(b.header("Authorization", "Bearer " + s.accessToken()));
    }

    private ResultActions createRole(Session s, String name, String... permissions) throws Exception {
        String perms = String.join("\",\"", permissions);
        return as(s, post("/api/v1/roles").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"%s","description":"d","permissions":[%s]}""".formatted(name,
                permissions.length == 0 ? "" : "\"" + perms + "\"")));
    }

    private UUID createdId(ResultActions result) throws Exception {
        return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id"));
    }

    private UUID systemRoleId(String name) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select id from roles where name = ?", UUID.class, name);
    }

    @Test
    void ownerCreatesListsRenamesAndDeletesACustomRole() throws Exception {
        UUID id = createdId(createRole(owner, "Support", "identity.user.read", "tenant.settings.read")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.system").value(false))
                .andExpect(jsonPath("$.permissions", Matchers.contains("identity.user.read", "tenant.settings.read"))));
        as(owner, get("/api/v1/roles")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", Matchers.hasItems("TENANT_OWNER", "TENANT_ADMIN", "Support")));
        as(owner, patch("/api/v1/roles/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Helpdesk\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Helpdesk"));
        as(owner, delete("/api/v1/roles/" + id)).andExpect(status().isNoContent());
        as(owner, get("/api/v1/roles/" + id)).andExpect(status().isNotFound());
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForList("select action from audit_events", String.class))
                .contains("RoleCreated", "RoleUpdated", "RoleDeleted");
    }

    @Test
    void validationAndUniqueness() throws Exception {
        createRole(owner, "Support", "identity.user.read").andExpect(status().isCreated());
        createRole(owner, "support", "identity.user.read").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        createRole(owner, "tenant_owner", "identity.user.read").andExpect(status().isConflict());
        createRole(owner, "Bogus", "no.such.permission").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("permissions"));
        createRole(owner, " ", "identity.user.read").andExpect(status().isBadRequest());
        as(owner, get("/api/v1/roles/not-a-uuid")).andExpect(status().isNotFound());
    }

    @Test
    void systemRolesAreImmutable() throws Exception {
        UUID ownerRole = systemRoleId("TENANT_OWNER");
        as(owner, patch("/api/v1/roles/" + ownerRole).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(SYSTEM));
        as(owner, put("/api/v1/roles/" + ownerRole + "/permissions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[]}")).andExpect(status().isConflict());
        as(owner, delete("/api/v1/roles/" + systemRoleId("TENANT_ADMIN"))).andExpect(status().isConflict());
    }

    @Test
    void assignedRolesCannotBeDeleted() throws Exception {
        UUID id = createdId(createRole(owner, "Assigned", "identity.user.read"));
        members.create(ws.tenantId(), Set.of(id));
        as(owner, delete("/api/v1/roles/" + id)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Remove this role from 1 user(s) first."));
    }

    @Test
    void ownersCanPrepareRolesForModulesThatAreNotEnabled() throws Exception {
        createRole(owner, "Sales", "crm.customer.read").andExpect(status().isCreated());
    }

    @Test
    void membersCannotGrantPermissionsTheyLack() throws Exception {
        UUID managerRole = createdId(createRole(owner, "RoleManager",
                "authorization.role.read", "authorization.role.manage", "identity.user.read"));
        Session manager = TestTenants.login(mvc, members.create(ws.tenantId(), Set.of(managerRole)));
        createRole(manager, "TooStrong", "tenant.settings.update").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(ESCALATION));
        UUID weak = createdId(createRole(manager, "Weak", "identity.user.read").andExpect(status().isCreated()));
        as(manager, put("/api/v1/roles/" + weak + "/permissions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[\"identity.user.read\",\"audit.event.read\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void permissionRemovalTakesEffectImmediately() throws Exception {
        UUID viewer = createdId(createRole(owner, "Viewer", "tenant.settings.read"));
        Session member = TestTenants.login(mvc, members.create(ws.tenantId(), Set.of(viewer)));
        as(member, get("/api/v1/tenant")).andExpect(status().isOk());
        as(owner, put("/api/v1/roles/" + viewer + "/permissions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[]}")).andExpect(status().isOk());
        as(member, get("/api/v1/tenant")).andExpect(status().isForbidden());
    }

    @Test
    void permissionCatalogShowsModuleState() throws Exception {
        as(owner, get("/api/v1/permissions")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'crm.customer.read')].moduleEnabled", Matchers.contains(false)))
                .andExpect(jsonPath("$[?(@.code == 'tenant.settings.read')].moduleEnabled", Matchers.contains(true)));
    }
}
