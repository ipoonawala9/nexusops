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
    UUID leadB, opportunityB, stageB;
    UUID warehouseB, goodsB, purchaseOrderB, purchaseLineB, salesOrderB, ruleB;
    UUID ticketB, articleB, categoryB;

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
        leadB = com.nexusops.support.Api.id(apiB.post("/api/v1/leads", "{\"companyName\":\"Beta prospect\"}"));
        opportunityB = com.nexusops.support.Api.id(apiB.post("/api/v1/opportunities",
                "{\"name\":\"Beta deal\",\"accountId\":\"" + orgB + "\"}"));
        stageB = UUID.fromString(com.nexusops.support.Api.read(apiB.get("/api/v1/crm/pipeline/stages"), "$[0].id"));
        apiB.post("/api/v1/activities", "{\"subjectType\":\"OPPORTUNITY\",\"subjectId\":\"" + opportunityB
                + "\",\"type\":\"NOTE\",\"summary\":\"B deal secret\"}").andExpect(status().isCreated());
        as(ownerB, put("/api/v1/tenant/modules/INVENTORY").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isOk());
        warehouseB = UUID.fromString(com.nexusops.support.Api.read(apiB.get("/api/v1/inventory/warehouses"), "$[0].id"));
        goodsB = com.nexusops.support.Api.id(apiB.post("/api/v1/products",
                "{\"sku\":\"B-G\",\"name\":\"Beta goods\",\"kind\":\"GOODS\"}"));
        apiB.post("/api/v1/inventory/adjustments", "{\"productId\":\"" + goodsB + "\",\"warehouseId\":\"" + warehouseB
                + "\",\"countedQuantity\":5,\"reason\":\"Opening\"}").andExpect(status().isOk());
        purchaseOrderB = com.nexusops.support.Api.id(apiB.post("/api/v1/purchase-orders", "{\"supplierId\":\"" + orgB
                + "\",\"warehouseId\":\"" + warehouseB + "\",\"lines\":[{\"productId\":\"" + goodsB
                + "\",\"quantity\":2,\"unitCost\":1}]}"));
        purchaseLineB = UUID.fromString(com.nexusops.support.Api.<java.util.List<String>>read(
                apiB.get("/api/v1/purchase-orders/" + purchaseOrderB), "$.lines[*].id").get(0));
        salesOrderB = com.nexusops.support.Api.id(apiB.post("/api/v1/sales-orders", "{\"customerId\":\"" + orgB
                + "\",\"warehouseId\":\"" + warehouseB + "\",\"lines\":[{\"productId\":\"" + goodsB
                + "\",\"quantity\":1,\"unitPrice\":1}]}"));
        ruleB = com.nexusops.support.Api.id(apiB.put("/api/v1/inventory/reorder-rules", "{\"productId\":\"" + goodsB
                + "\",\"warehouseId\":\"" + warehouseB + "\",\"minQuantity\":1,\"maxQuantity\":9}"));
        // the FREE plan allows two modules; tenant B now runs CRM, Inventory and HelpDesk
        OwnerJdbc.jdbc().update("update tenants set plan_code = 'ENTERPRISE' where id = ?", b.tenantId());
        as(ownerB, put("/api/v1/tenant/modules/HELPDESK").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isOk());
        categoryB = UUID.fromString(com.nexusops.support.Api.<java.util.List<String>>read(
                apiB.get("/api/v1/helpdesk/categories"), "$[?(@.name == 'General')].id").get(0));
        ticketB = com.nexusops.support.Api.id(apiB.post("/api/v1/helpdesk/tickets",
                "{\"subject\":\"Beta printer issue\",\"description\":\"Beta desc\",\"requesterId\":\"" + orgB + "\"}"));
        articleB = com.nexusops.support.Api.id(apiB.post("/api/v1/helpdesk/articles",
                "{\"title\":\"Beta guide\",\"body\":\"Beta body\"}"));
        apiB.post("/api/v1/helpdesk/articles/" + articleB + "/publish", "{\"version\":0}").andExpect(status().isOk());
    }

    private ResultActions as(Session s,
            org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder<?> b) throws Exception {
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
        as(ownerA, post("/api/v1/products/" + productB + "/restore")).andExpect(status().isNotFound());
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
        as(ownerA, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/documents")
                .file(new org.springframework.mock.web.MockMultipartFile("file", "a.txt", "text/plain",
                        "A".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .param("subjectType", "PARTY").param("subjectId", orgB.toString())).andExpect(status().isNotFound());

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
        as(ownerB, get("/api/v1/parties/" + personB)).andExpect(jsonPath("$.firstName").value("Bea"))
                .andExpect(jsonPath("$.archivedAt").doesNotExist()).andExpect(jsonPath("$.roles", Matchers.empty()));
        as(ownerB, get("/api/v1/products/" + productB)).andExpect(jsonPath("$.name").value("Beta widget"))
                .andExpect(jsonPath("$.archivedAt").doesNotExist());
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

    @Test
    void crmRecordsOfAnotherTenantAreInvisibleAndUntouchable() throws Exception {
        as(ownerA, put("/api/v1/tenant/modules/CRM").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk());
        as(ownerA, get("/api/v1/leads/" + leadB)).andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/leads/" + leadB), "{\"lastName\":\"Hacked\",\"version\":0}")).andExpect(status().isNotFound());
        as(ownerA, json(post("/api/v1/leads/" + leadB + "/status"), "{\"status\":\"CONTACTED\",\"version\":0}"))
                .andExpect(status().isNotFound());
        as(ownerA, json(post("/api/v1/leads/" + leadB + "/convert"), "{\"organization\":{\"name\":\"X\"},\"version\":0}"))
                .andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/opportunities/" + opportunityB)).andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/opportunities/" + opportunityB), "{\"name\":\"H\",\"accountId\":\"" + orgB
                + "\",\"version\":0}")).andExpect(status().isNotFound());
        as(ownerA, json(post("/api/v1/opportunities/" + opportunityB + "/stage"), "{\"stageId\":\"" + stageB
                + "\",\"version\":0}")).andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/crm/pipeline/stages/" + stageB), "{\"name\":\"H\",\"probability\":1,\"version\":0}"))
                .andExpect(status().isNotFound());
        as(ownerA, delete("/api/v1/crm/pipeline/stages/" + stageB)).andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/crm/customers/" + orgB)).andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/activities").param("subjectType", "OPPORTUNITY").param("subjectId", opportunityB.toString()))
                .andExpect(status().isNotFound());

        // references to another tenant's rows inside request bodies are refused
        as(ownerA, json(post("/api/v1/opportunities"), "{\"name\":\"X\",\"accountId\":\"" + orgB + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("accountId"));
        String orgA = JsonPath.read(as(ownerA, json(post("/api/v1/organizations"), "{\"name\":\"Alpha\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        as(ownerA, json(post("/api/v1/opportunities"), "{\"name\":\"X\",\"accountId\":\"" + orgA + "\",\"stageId\":\""
                + stageB + "\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("stageId"));
        as(ownerA, json(post("/api/v1/leads"), "{\"lastName\":\"X\",\"ownerId\":\"" + userBId + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("ownerId"));
        String leadA = JsonPath.read(as(ownerA, json(post("/api/v1/leads"), "{\"lastName\":\"Alpha lead\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        as(ownerA, json(post("/api/v1/leads/" + leadA + "/convert"), "{\"organization\":{\"existingId\":\"" + orgB
                + "\"},\"version\":0}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("organization.existingId"));

        // lists, search, board and dashboard never contain B's rows
        as(ownerA, get("/api/v1/leads")).andExpect(jsonPath("$.items[*].name", Matchers.not(Matchers.hasItem("Beta prospect"))));
        as(ownerA, get("/api/v1/opportunities")).andExpect(jsonPath("$.total").value(0));
        as(ownerB, get("/api/v1/search").param("q", "beta")).andExpect(jsonPath("$.length()", Matchers.greaterThan(0)));
        as(ownerA, get("/api/v1/search").param("q", "beta")).andExpect(jsonPath("$.length()").value(0));
        as(ownerA, get("/api/v1/crm/pipeline/board")).andExpect(jsonPath("$.columns[0].count").value(0));
        as(ownerA, get("/api/v1/crm/customers")).andExpect(jsonPath("$.total").value(0));

        // tenant B is untouched
        as(ownerB, get("/api/v1/leads/" + leadB)).andExpect(jsonPath("$.status").value("NEW"));
        as(ownerB, get("/api/v1/opportunities/" + opportunityB)).andExpect(jsonPath("$.name").value("Beta deal"))
                .andExpect(jsonPath("$.stage.id").value(stageB.toString()));
    }

    @Test
    void inventoryRecordsOfAnotherTenantAreInvisibleAndUntouchable() throws Exception {
        as(ownerA, put("/api/v1/tenant/modules/INVENTORY").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isOk());
        as(ownerA, json(put("/api/v1/inventory/warehouses/" + warehouseB), "{\"code\":\"H\",\"name\":\"H\",\"version\":0}"))
                .andExpect(status().isNotFound());
        as(ownerA, post("/api/v1/inventory/warehouses/" + warehouseB + "/archive")).andExpect(status().isNotFound());
        as(ownerA, post("/api/v1/inventory/warehouses/" + warehouseB + "/restore")).andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/inventory/stock/products/" + goodsB)).andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/purchase-orders/" + purchaseOrderB)).andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/purchase-orders/" + purchaseOrderB), "{\"supplierId\":\"" + orgB
                + "\",\"warehouseId\":\"" + warehouseB + "\",\"lines\":[],\"version\":0}")).andExpect(status().isNotFound());
        for (String action : new String[] {"order", "cancel"}) {
            as(ownerA, json(post("/api/v1/purchase-orders/" + purchaseOrderB + "/" + action), "{\"version\":0}"))
                    .andExpect(status().isNotFound());
        }
        as(ownerA, json(post("/api/v1/purchase-orders/" + purchaseOrderB + "/receipts"), "{\"lines\":[{\"lineId\":\""
                + purchaseLineB + "\",\"quantity\":1}],\"version\":0}")).andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/sales-orders/" + salesOrderB)).andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/sales-orders/" + salesOrderB), "{\"customerId\":\"" + orgB
                + "\",\"warehouseId\":\"" + warehouseB + "\",\"lines\":[],\"version\":0}")).andExpect(status().isNotFound());
        for (String action : new String[] {"confirm", "fulfil", "cancel"}) {
            as(ownerA, json(post("/api/v1/sales-orders/" + salesOrderB + "/" + action), "{\"version\":0}"))
                    .andExpect(status().isNotFound());
        }
        as(ownerA, delete("/api/v1/inventory/reorder-rules/" + ruleB)).andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/activities").param("subjectType", "PURCHASE_ORDER")
                .param("subjectId", purchaseOrderB.toString())).andExpect(status().isNotFound());

        // references to another tenant's rows inside request bodies are refused
        String warehouseA = JsonPath.<java.util.List<String>>read(as(ownerA, get("/api/v1/inventory/warehouses"))
                .andReturn().getResponse().getContentAsString(), "$[*].id").get(0);
        as(ownerA, json(post("/api/v1/inventory/adjustments"), "{\"productId\":\"" + goodsB + "\",\"warehouseId\":\""
                + warehouseA + "\",\"countedQuantity\":1,\"reason\":\"x\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("productId"));
        String goodsA = JsonPath.read(as(ownerA, json(post("/api/v1/products"),
                "{\"sku\":\"A-G\",\"name\":\"Alpha goods\",\"kind\":\"GOODS\"}")).andReturn().getResponse()
                .getContentAsString(), "$.id");
        as(ownerA, json(post("/api/v1/inventory/transfers"), "{\"productId\":\"" + goodsA + "\",\"fromWarehouseId\":\""
                + warehouseA + "\",\"toWarehouseId\":\"" + warehouseB + "\",\"quantity\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("toWarehouseId"));
        as(ownerA, json(post("/api/v1/purchase-orders"), "{\"supplierId\":\"" + orgB + "\",\"warehouseId\":\""
                + warehouseA + "\",\"lines\":[{\"productId\":\"" + goodsA + "\",\"quantity\":1,\"unitCost\":1}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("supplierId"));
        as(ownerA, json(put("/api/v1/inventory/reorder-rules"), "{\"productId\":\"" + goodsA + "\",\"warehouseId\":\""
                + warehouseB + "\",\"minQuantity\":1,\"maxQuantity\":2}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("warehouseId"));

        // lists, search and the overview never contain B's rows
        as(ownerA, get("/api/v1/inventory/stock")).andExpect(jsonPath("$.total").value(0));
        as(ownerA, get("/api/v1/inventory/movements")).andExpect(jsonPath("$.total").value(0));
        as(ownerA, get("/api/v1/purchase-orders")).andExpect(jsonPath("$.total").value(0));
        as(ownerA, get("/api/v1/sales-orders")).andExpect(jsonPath("$.total").value(0));
        as(ownerA, get("/api/v1/inventory/reorder-rules")).andExpect(jsonPath("$.length()").value(0));
        as(ownerA, get("/api/v1/inventory/warehouses")).andExpect(jsonPath("$.length()").value(1));
        as(ownerA, get("/api/v1/search").param("q", "o-0000")).andExpect(jsonPath("$.length()").value(0));
        as(ownerA, get("/api/v1/inventory/overview")).andExpect(jsonPath("$.recentMovements.length()").value(0));

        // tenant B is untouched
        as(ownerB, get("/api/v1/purchase-orders/" + purchaseOrderB)).andExpect(jsonPath("$.status").value("DRAFT"));
        as(ownerB, get("/api/v1/inventory/stock/products/" + goodsB)).andExpect(jsonPath("$.onHand").value(5.0));
    }

    @Test
    void helpDeskRecordsOfAnotherTenantAreInvisibleAndUntouchable() throws Exception {
        as(ownerA, put("/api/v1/tenant/modules/HELPDESK").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isOk());
        as(ownerA, get("/api/v1/helpdesk/tickets/" + ticketB)).andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/helpdesk/tickets/" + ticketB + "/context")).andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/helpdesk/tickets/" + ticketB + "/messages")).andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/helpdesk/tickets/" + ticketB), "{\"subject\":\"H\",\"description\":\"H\","
                + "\"requesterId\":\"" + orgB + "\",\"version\":0}")).andExpect(status().isNotFound());
        as(ownerA, json(post("/api/v1/helpdesk/tickets/" + ticketB + "/messages"),
                "{\"kind\":\"PUBLIC_REPLY\",\"body\":\"x\"}")).andExpect(status().isNotFound());
        as(ownerA, json(post("/api/v1/helpdesk/tickets/" + ticketB + "/assign"), "{\"assigneeId\":null,\"version\":0}"))
                .andExpect(status().isNotFound());
        as(ownerA, json(post("/api/v1/helpdesk/tickets/" + ticketB + "/status"), "{\"status\":\"PENDING\",\"version\":0}"))
                .andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/helpdesk/categories/" + categoryB), "{\"name\":\"H\",\"version\":0}"))
                .andExpect(status().isNotFound());
        as(ownerA, post("/api/v1/helpdesk/categories/" + categoryB + "/archive")).andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/helpdesk/articles/" + articleB)).andExpect(status().isNotFound());
        as(ownerA, json(post("/api/v1/helpdesk/articles/" + articleB + "/archive"), "{\"version\":1}"))
                .andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/activities").param("subjectType", "TICKET").param("subjectId", ticketB.toString()))
                .andExpect(status().isNotFound());
        // references to another tenant's rows inside bodies are refused
        as(ownerA, json(post("/api/v1/helpdesk/tickets"), "{\"subject\":\"x\",\"description\":\"x\",\"requesterId\":\""
                + orgB + "\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("requesterId"));
        String orgA = JsonPath.read(as(ownerA, json(post("/api/v1/organizations"), "{\"name\":\"Alpha\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        as(ownerA, json(post("/api/v1/helpdesk/tickets"), "{\"subject\":\"x\",\"description\":\"x\",\"requesterId\":\""
                + orgA + "\",\"categoryId\":\"" + categoryB + "\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("categoryId"));
        as(ownerA, json(post("/api/v1/helpdesk/tickets"), "{\"subject\":\"x\",\"description\":\"x\",\"requesterId\":\""
                + orgA + "\",\"linkedType\":\"TICKET\",\"linkedId\":\"" + ticketB + "\"}")).andExpect(status().isBadRequest());
        // lists, search and the dashboard never contain B's rows
        as(ownerA, get("/api/v1/helpdesk/tickets")).andExpect(jsonPath("$.total").value(0));
        as(ownerA, get("/api/v1/helpdesk/articles")).andExpect(jsonPath("$.total").value(0));
        as(ownerA, get("/api/v1/search").param("q", "beta")).andExpect(jsonPath("$[*].type",
                Matchers.not(Matchers.hasItems("TICKET", "KB_ARTICLE"))));
        as(ownerA, get("/api/v1/helpdesk/dashboard")).andExpect(jsonPath("$.last30Days.created").value(0));
        // tenant B is untouched
        as(ownerB, get("/api/v1/helpdesk/tickets/" + ticketB)).andExpect(jsonPath("$.status").value("NEW"));
    }
}
