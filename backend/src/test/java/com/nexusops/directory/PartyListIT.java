package com.nexusops.directory;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class PartyListIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    Api owner;
    UUID acme;
    UUID globex;
    UUID ada;
    UUID bob;

    @BeforeEach
    void directory() throws Exception {
        Workspace ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("plist"));
        owner = Api.login(mvc, ws);
        acme = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Acme\",\"domain\":\"acme.com\"}"));
        globex = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Globex 50%_Off\"}"));
        ada = Api.id(owner.post("/api/v1/persons",
                "{\"firstName\":\"Ada\",\"lastName\":\"Lovelace\",\"email\":\"ada@acme.com\",\"organizationId\":\"%s\"}"
                        .formatted(acme)));
        bob = Api.id(owner.post("/api/v1/persons", "{\"firstName\":\"Bob\"}"));
    }

    @Test
    void listsActivePartiesByNameWithTheirOrganization() throws Exception {
        owner.get("/api/v1/parties").andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.items[*].name", Matchers.contains("Acme", "Ada Lovelace", "Bob", "Globex 50%_Off")))
                .andExpect(jsonPath("$.items[1].organization.name").value("Acme"))
                .andExpect(jsonPath("$.items[1].email").value("ada@acme.com"))
                .andExpect(jsonPath("$.items[1].archived").value(false))
                .andExpect(jsonPath("$.items[1].roles", Matchers.empty()));
    }

    @Test
    void filtersByKindOrganizationAndSearchText() throws Exception {
        owner.get("/api/v1/parties?kind=ORGANIZATION")
                .andExpect(jsonPath("$.items[*].name", Matchers.contains("Acme", "Globex 50%_Off")));
        owner.get("/api/v1/parties?organizationId=" + acme)
                .andExpect(jsonPath("$.items[*].id", Matchers.contains(ada.toString())));
        owner.get("/api/v1/parties?q=ACME.COM")
                .andExpect(jsonPath("$.items[*].name", Matchers.contains("Acme", "Ada Lovelace")));
        owner.get("/api/v1/parties?q=lovel").andExpect(jsonPath("$.items[*].id", Matchers.contains(ada.toString())));
        // LIKE wildcards are literal
        owner.get("/api/v1/parties?q=%_").andExpect(jsonPath("$.items[*].id", Matchers.contains(globex.toString())));
        owner.get("/api/v1/parties?kind=ROBOT").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("kind"));
    }

    @Test
    void pagesThroughResults() throws Exception {
        owner.get("/api/v1/parties?size=2&page=1")
                .andExpect(jsonPath("$.items[*].name", Matchers.contains("Bob", "Globex 50%_Off")))
                .andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.total").value(4));
    }

    @Test
    void archivingHidesFromListsKeepsItReadableAndBlocksEdits() throws Exception {
        owner.post("/api/v1/parties/" + bob + "/archive", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").exists());
        owner.get("/api/v1/parties").andExpect(jsonPath("$.items[*].id", Matchers.not(Matchers.hasItem(bob.toString()))));
        owner.get("/api/v1/parties?archived=true")
                .andExpect(jsonPath("$.items[*].id", Matchers.contains(bob.toString())))
                .andExpect(jsonPath("$.items[0].archived").value(true));
        owner.get("/api/v1/parties/" + bob).andExpect(status().isOk());
        owner.put("/api/v1/persons/" + bob, "{\"firstName\":\"Robert\",\"version\":1}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("This record is archived."));
        // archiving again is a no-op
        owner.post("/api/v1/parties/" + bob + "/archive", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void anArchivedOrganizationCannotBeLinkedButStillCountsAsADuplicate() throws Exception {
        owner.post("/api/v1/parties/" + acme + "/archive", "").andExpect(status().isOk());
        owner.post("/api/v1/persons", "{\"firstName\":\"Cy\",\"organizationId\":\"%s\"}".formatted(acme))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("This organization is archived."));
        owner.post("/api/v1/organizations", "{\"name\":\"ACME Ltd\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.duplicates[0].id").value(acme.toString()))
                .andExpect(jsonPath("$.duplicates[0].archived").value(true));
        // the person already linked to it keeps the link through an edit
        owner.put("/api/v1/persons/" + ada, """
                {"firstName":"Ada","lastName":"Lovelace","email":"ada@acme.com","organizationId":"%s","version":0}"""
                .formatted(acme)).andExpect(status().isOk());
    }

    @Test
    void restoringBringsItBack() throws Exception {
        owner.post("/api/v1/parties/" + bob + "/archive", "");
        owner.post("/api/v1/parties/" + bob + "/restore", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").doesNotExist());
        owner.get("/api/v1/parties").andExpect(jsonPath("$.items[*].id", Matchers.hasItem(bob.toString())));
        owner.post("/api/v1/parties/" + UUID.randomUUID() + "/restore", "").andExpect(status().isNotFound());
    }
}
