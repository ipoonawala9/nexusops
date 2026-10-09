package com.nexusops.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHelpDesk;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class CategoryApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hdcat"));
        owner = Api.login(mvc, ws);
        TestHelpDesk.enable(owner);
    }

    private long audits(String action) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = ?", Long.class, action);
    }

    @Test
    void everyWorkspaceStartsWithFourCategoriesAndTheDefaultSlaPolicies() throws Exception {
        owner.get("/api/v1/helpdesk/categories").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(Matchers.contains("General", "Billing", "Product issue", "Delivery")));
        owner.get("/api/v1/helpdesk/sla-policies")
                .andExpect(jsonPath("$[*].priority").value(Matchers.contains("URGENT", "HIGH", "NORMAL", "LOW")))
                .andExpect(jsonPath("$[0].firstResponseMinutes").value(60))
                .andExpect(jsonPath("$[0].resolutionMinutes").value(240))
                .andExpect(jsonPath("$[3].resolutionMinutes").value(7200));
    }

    @Test
    void createsRenamesArchivesAndRestoresWithADefaultAssignee() throws Exception {
        UUID ownerId = OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select id from users where email = ?", UUID.class,
                ws.email());
        UUID id = Api.id(owner.post("/api/v1/helpdesk/categories", "{\"name\":\"  Warranty   claims \","
                        + "\"description\":\"Repairs under warranty\",\"defaultAssigneeId\":\"" + ownerId + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Warranty claims"))
                .andExpect(jsonPath("$.defaultAssignee.id").value(ownerId.toString()))
                .andExpect(jsonPath("$.position").value(4)));
        owner.post("/api/v1/helpdesk/categories", "{\"name\":\"WARRANTY CLAIMS\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        owner.put("/api/v1/helpdesk/categories/" + id, "{\"name\":\"Warranty\",\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultAssignee").doesNotExist());
        owner.put("/api/v1/helpdesk/categories/" + id, "{\"name\":\"Warranty\",\"version\":0}")
                .andExpect(status().isConflict());
        owner.post("/api/v1/helpdesk/categories/" + id + "/archive", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").exists());
        owner.get("/api/v1/helpdesk/categories").andExpect(jsonPath("$[*].name", Matchers.not(Matchers.hasItem("Warranty"))));
        owner.get("/api/v1/helpdesk/categories?archived=true").andExpect(jsonPath("$[*].name").value(Matchers.contains("Warranty")));
        owner.post("/api/v1/helpdesk/categories/" + id + "/restore", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").doesNotExist());
        assertThat(audits("TicketCategoryCreated")).isEqualTo(1);
        assertThat(audits("TicketCategoryUpdated")).isEqualTo(1);
        assertThat(audits("TicketCategoryArchived")).isEqualTo(1);
        assertThat(audits("TicketCategoryRestored")).isEqualTo(1);
    }

    @Test
    void invalidCategoriesAreFieldErrors() throws Exception {
        String[][] cases = {
                {"{\"name\":\"\"}", "name"},
                {"{\"name\":\"" + "x".repeat(81) + "\"}", "name"},
                {"{\"name\":\"Ok\",\"description\":\"" + "x".repeat(501) + "\"}", "description"},
                {"{\"name\":\"Ok\",\"defaultAssigneeId\":\"" + UUID.randomUUID() + "\"}", "defaultAssigneeId"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/helpdesk/categories", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
        owner.put("/api/v1/helpdesk/categories/" + UUID.randomUUID(), "{\"name\":\"X\",\"version\":0}")
                .andExpect(status().isNotFound());
    }

    @Test
    void editingAnArchivedCategoryChecksTheBodyFirst() throws Exception {
        UUID id = Api.id(owner.post("/api/v1/helpdesk/categories", "{\"name\":\"Returns\"}")
                .andExpect(status().isCreated()));
        owner.post("/api/v1/helpdesk/categories/" + id + "/archive", "").andExpect(status().isOk());
        owner.put("/api/v1/helpdesk/categories/" + id, "{\"name\":\"\",\"version\":1}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("name"));
        owner.put("/api/v1/helpdesk/categories/" + id, "{\"name\":\"Returns\",\"defaultAssigneeId\":\""
                + UUID.randomUUID() + "\",\"version\":1}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("defaultAssigneeId"));
        owner.put("/api/v1/helpdesk/categories/" + id, "{\"name\":\"Returns\",\"version\":1}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("This record is archived."));
    }

    @Test
    void settingsNeedTheirPermission() throws Exception {
        UUID readerRole = TestRoles.create(mvc, owner.session(), "Agent", "helpdesk.ticket.read");
        Api agent = Api.login(mvc, members.create(ws.tenantId(), Set.of(readerRole)));
        agent.get("/api/v1/helpdesk/categories").andExpect(status().isOk());
        agent.get("/api/v1/helpdesk/sla-policies").andExpect(status().isOk());
        agent.post("/api/v1/helpdesk/categories", "{\"name\":\"X\"}").andExpect(status().isForbidden());
        agent.put("/api/v1/helpdesk/sla-policies/LOW", "{\"firstResponseMinutes\":1,\"resolutionMinutes\":2,\"version\":0}")
                .andExpect(status().isForbidden());
    }
}
