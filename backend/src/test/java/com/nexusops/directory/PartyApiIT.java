package com.nexusops.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class PartyApiIT extends IntegrationTestSupport {

    static final String DUPLICATE =
            "This looks like a record that already exists. Open it, or give a reason for keeping a separate one.";
    static final String STALE = "This record was changed by someone else. Reload and try again.";

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("party"));
        owner = Api.login(mvc, ws);
    }

    private UUID organization(String json) throws Exception {
        return Api.id(owner.post("/api/v1/organizations", json).andExpect(status().isCreated()));
    }

    private UUID person(String json) throws Exception {
        return Api.id(owner.post("/api/v1/persons", json).andExpect(status().isCreated()));
    }

    @Test
    void createsAnOrganizationWithNormalizedContactDetails() throws Exception {
        owner.post("/api/v1/organizations", """
                {"name":"  Acme, Inc. ","domain":"https://WWW.Acme.com/about","website":"acme.com",
                 "email":"Sales@Acme.com","phone":" +1 (555) 0100 "}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("ORGANIZATION"))
                .andExpect(jsonPath("$.name").value("Acme, Inc."))
                .andExpect(jsonPath("$.domain").value("acme.com"))
                .andExpect(jsonPath("$.website").value("https://acme.com"))
                .andExpect(jsonPath("$.email").value("sales@acme.com"))
                .andExpect(jsonPath("$.phone").value("+1 (555) 0100"))
                .andExpect(jsonPath("$.roles", Matchers.empty()))
                .andExpect(jsonPath("$.archivedAt").doesNotExist())
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void createsAPersonLinkedToAnOrganizationAndReadsItBack() throws Exception {
        UUID acme = organization("{\"name\":\"Acme\"}");
        UUID ada = person("""
                {"firstName":"Ada","lastName":"Lovelace","jobTitle":"CTO","organizationId":"%s","email":"ada@acme.com"}"""
                .formatted(acme));
        owner.get("/api/v1/parties/" + ada).andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("PERSON"))
                .andExpect(jsonPath("$.name").value("Ada Lovelace"))
                .andExpect(jsonPath("$.firstName").value("Ada"))
                .andExpect(jsonPath("$.lastName").value("Lovelace"))
                .andExpect(jsonPath("$.jobTitle").value("CTO"))
                .andExpect(jsonPath("$.organization.id").value(acme.toString()))
                .andExpect(jsonPath("$.organization.name").value("Acme"));
        Long audited = OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = 'PersonCreated' and entity_id = ?", Long.class,
                ada.toString());
        assertThat(audited).isEqualTo(1);
    }

    @Test
    void invalidInputIsAFieldError() throws Exception {
        owner.post("/api/v1/persons", "{\"firstName\":\"  \"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("firstName"));
        owner.post("/api/v1/persons", "{\"firstName\":\"Ada\",\"email\":\"nope\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("email"));
        owner.post("/api/v1/organizations", "{\"name\":\"Acme\",\"domain\":\"not a domain\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("domain"));
        owner.post("/api/v1/organizations", "{\"name\":\"Acme\",\"website\":\"javascript:alert(1)\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("website"));
    }

    @Test
    void aPersonMustBelongToAnOrganizationOfThisWorkspace() throws Exception {
        UUID ada = person("{\"firstName\":\"Ada\"}");
        owner.post("/api/v1/persons", "{\"firstName\":\"Bob\",\"organizationId\":\"%s\"}".formatted(ada))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("organizationId"));
        owner.post("/api/v1/persons", "{\"firstName\":\"Bob\",\"organizationId\":\"%s\"}".formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("organizationId"));
    }

    @Test
    void aPersonWithTheSameEmailIsAProbableDuplicate() throws Exception {
        UUID first = person("{\"firstName\":\"Ada\",\"lastName\":\"Lovelace\",\"email\":\"ada@acme.com\"}");
        owner.post("/api/v1/persons", "{\"firstName\":\"Augusta\",\"email\":\"ADA@acme.com\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(DUPLICATE))
                .andExpect(jsonPath("$.duplicates[0].id").value(first.toString()))
                .andExpect(jsonPath("$.duplicates[0].kind").value("PERSON"))
                .andExpect(jsonPath("$.duplicates[0].name").value("Ada Lovelace"))
                .andExpect(jsonPath("$.duplicates[0].archived").value(false));
    }

    @Test
    void aReasonKeepsASeparateRecordAndIsStoredAndAudited() throws Exception {
        UUID first = person("{\"firstName\":\"Ada\",\"email\":\"ada@acme.com\"}");
        UUID second = Api.id(owner.post("/api/v1/persons",
                        "{\"firstName\":\"Ada\",\"email\":\"ada@acme.com\",\"duplicateReason\":\" Shared inbox \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.duplicateReason").value("Shared inbox")));
        String metadata = OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select metadata::text from audit_events where action = 'PersonCreated' and entity_id = ?", String.class,
                second.toString());
        assertThat(metadata).contains(first.toString()).contains("Shared inbox");
    }

    @Test
    void theSameNameIsADuplicateOnlyWithinTheSameOrganization() throws Exception {
        UUID acme = organization("{\"name\":\"Acme\"}");
        UUID globex = organization("{\"name\":\"Globex\"}");
        person("{\"firstName\":\"John\",\"lastName\":\"Smith\",\"organizationId\":\"%s\"}".formatted(acme));
        owner.post("/api/v1/persons", "{\"firstName\":\" john \",\"lastName\":\"SMITH\",\"organizationId\":\"%s\"}"
                .formatted(acme)).andExpect(status().isConflict());
        owner.post("/api/v1/persons", "{\"firstName\":\"John\",\"lastName\":\"Smith\",\"organizationId\":\"%s\"}"
                .formatted(globex)).andExpect(status().isCreated());
    }

    @Test
    void organizationsMatchOnNameVariantsAndDomain() throws Exception {
        UUID acme = organization("{\"name\":\"Acme\",\"domain\":\"acme.com\"}");
        owner.post("/api/v1/organizations", "{\"name\":\"ACME, Inc.\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.duplicates[0].id").value(acme.toString()))
                .andExpect(jsonPath("$.duplicates[0].domain").value("acme.com"));
        owner.post("/api/v1/organizations", "{\"name\":\"Acme Europe\",\"domain\":\"https://www.acme.com\"}")
                .andExpect(status().isConflict());
        owner.post("/api/v1/organizations", "{\"name\":\"Acme Robotics\",\"domain\":\"acme-robotics.com\"}")
                .andExpect(status().isCreated());
    }

    @Test
    void concurrentIdenticalCreatesLetExactlyOneThrough() throws Exception {
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return owner.post("/api/v1/persons", "{\"firstName\":\"Race\",\"email\":\"race@acme.com\"}")
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsOnly(201, 409).filteredOn(s -> s == 201).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void updatesReplaceFieldsAndBumpTheVersion() throws Exception {
        UUID acme = organization("{\"name\":\"Acme\"}");
        UUID ada = person("{\"firstName\":\"Ada\",\"email\":\"ada@acme.com\",\"jobTitle\":\"CTO\"}");
        owner.put("/api/v1/persons/" + ada, """
                {"firstName":"Ada","lastName":"King","organizationId":"%s","version":0}""".formatted(acme))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ada King"))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.jobTitle").doesNotExist())
                .andExpect(jsonPath("$.organization.name").value("Acme"))
                .andExpect(jsonPath("$.version").value(1));
        owner.put("/api/v1/organizations/" + acme, "{\"name\":\"Acme Corp\",\"domain\":\"acme.com\",\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.domain").value("acme.com"));
    }

    @Test
    void aStaleOrMissingVersionIsRefused() throws Exception {
        UUID ada = person("{\"firstName\":\"Ada\"}");
        owner.put("/api/v1/persons/" + ada, "{\"firstName\":\"Ada\",\"version\":0}").andExpect(status().isOk());
        owner.put("/api/v1/persons/" + ada, "{\"firstName\":\"Augusta\",\"version\":0}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(STALE));
        owner.put("/api/v1/persons/" + ada, "{\"firstName\":\"Augusta\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("version"));
    }

    @Test
    void renamingIntoAnExistingIdentityNeedsAReasonButUnrelatedEditsDoNot() throws Exception {
        person("{\"firstName\":\"Ada\",\"email\":\"ada@acme.com\"}");
        UUID kept = Api.id(owner.post("/api/v1/persons",
                "{\"firstName\":\"Ada\",\"email\":\"ada@acme.com\",\"duplicateReason\":\"Shared inbox\"}"));
        // keeping the same identity: no duplicate check, so no reason needed again
        owner.put("/api/v1/persons/" + kept, "{\"firstName\":\"Ada\",\"email\":\"ada@acme.com\",\"phone\":\"555 0100\",\"version\":0}")
                .andExpect(status().isOk());
        UUID bob = person("{\"firstName\":\"Bob\",\"email\":\"bob@acme.com\"}");
        owner.put("/api/v1/persons/" + bob, "{\"firstName\":\"Bob\",\"email\":\"ada@acme.com\",\"version\":0}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.duplicates", Matchers.hasSize(2)));
    }

    @Test
    void wrongKindOrUnknownIdIs404() throws Exception {
        UUID acme = organization("{\"name\":\"Acme\"}");
        UUID ada = person("{\"firstName\":\"Ada\"}");
        owner.put("/api/v1/persons/" + acme, "{\"firstName\":\"Ada\",\"version\":0}").andExpect(status().isNotFound());
        owner.put("/api/v1/organizations/" + ada, "{\"name\":\"Ada\",\"version\":0}").andExpect(status().isNotFound());
        owner.get("/api/v1/parties/" + UUID.randomUUID()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Record not found."));
    }

    @Test
    void readersCannotWrite() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "Reader", "directory.party.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        UUID ada = person("{\"firstName\":\"Ada\"}");
        reader.get("/api/v1/parties/" + ada).andExpect(status().isOk());
        reader.post("/api/v1/persons", "{\"firstName\":\"Bob\"}").andExpect(status().isForbidden());
        reader.put("/api/v1/persons/" + ada, "{\"firstName\":\"Bob\",\"version\":0}").andExpect(status().isForbidden());
        reader.post("/api/v1/organizations", "{\"name\":\"Globex\"}").andExpect(status().isForbidden());
    }
}
