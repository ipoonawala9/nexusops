package com.nexusops.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestCrm;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class LeadConversionIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID lead;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("conv"));
        owner = Api.login(mvc, ws);
        TestCrm.enable(owner);
        lead = Api.id(owner.post("/api/v1/leads", "{\"firstName\":\"Grace\",\"lastName\":\"Hopper\",\"companyName\":"
                + "\"Acme Robotics\",\"email\":\"grace@acme.test\",\"estimatedValue\":5000}"));
    }

    private long count(String sql) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(sql, Long.class);
    }

    private static final String CREATE_BOTH = "{\"person\":{\"firstName\":\"Grace\",\"lastName\":\"Hopper\","
            + "\"email\":\"grace@acme.test\"},\"organization\":{\"name\":\"Acme Robotics\"},\"opportunity\":{\"amount\":5000},\"version\":0}";

    @Test
    void createsThePersonTheOrganizationAndAnOpportunity() throws Exception {
        owner.post("/api/v1/leads/" + lead + "/convert", CREATE_BOTH).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONVERTED"))
                .andExpect(jsonPath("$.convertedAt").exists())
                .andExpect(jsonPath("$.convertedPerson.name").value("Grace Hopper"))
                .andExpect(jsonPath("$.convertedOrganization.name").value("Acme Robotics"))
                .andExpect(jsonPath("$.convertedOpportunityId").exists());
        String body = Api.body(owner.get("/api/v1/leads/" + lead));
        String org = com.jayway.jsonpath.JsonPath.read(body, "$.convertedOrganization.id");
        String person = com.jayway.jsonpath.JsonPath.read(body, "$.convertedPerson.id");
        String opportunity = com.jayway.jsonpath.JsonPath.read(body, "$.convertedOpportunityId");
        owner.get("/api/v1/parties/" + org).andExpect(jsonPath("$.roles[?(@.role == 'CUSTOMER')].status")
                .value(Matchers.contains("ACTIVE")));
        owner.get("/api/v1/parties/" + person).andExpect(jsonPath("$.organization.id").value(org));
        owner.get("/api/v1/opportunities/" + opportunity).andExpect(jsonPath("$.name").value("Grace Hopper"))
                .andExpect(jsonPath("$.account.id").value(org)).andExpect(jsonPath("$.contact.id").value(person))
                .andExpect(jsonPath("$.amount").value(5000.0)).andExpect(jsonPath("$.leadId").value(lead.toString()))
                .andExpect(jsonPath("$.stage.name").value("Prospecting"));
        // the converted lead is frozen
        owner.put("/api/v1/leads/" + lead, "{\"lastName\":\"X\",\"version\":1}").andExpect(status().isConflict());
        owner.post("/api/v1/activities", "{\"subjectType\":\"LEAD\",\"subjectId\":\"" + lead
                + "\",\"type\":\"NOTE\",\"summary\":\"late\"}").andExpect(status().isConflict());
        owner.post("/api/v1/leads/" + lead + "/convert", CREATE_BOTH.replace("\"version\":0", "\"version\":1"))
                .andExpect(status().isConflict());
        assertThat(count("select count(*) from audit_events where action = 'LeadConverted'")).isEqualTo(1);
    }

    @Test
    void anOpportunityWithoutAnAmountHasNone() throws Exception {
        owner.post("/api/v1/leads/" + lead + "/convert", CREATE_BOTH.replace("{\"amount\":5000}", "{}"))
                .andExpect(status().isOk());
        String opportunity = Api.read(owner.get("/api/v1/leads/" + lead), "$.convertedOpportunityId");
        owner.get("/api/v1/opportunities/" + opportunity).andExpect(jsonPath("$.amount").doesNotExist())
                .andExpect(jsonPath("$.currency").doesNotExist());
    }

    @Test
    void aProbableDuplicateOrganizationIsRefusedThenLinked() throws Exception {
        UUID existing = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Acme Robotics Ltd\"}"));
        long partiesBefore = count("select count(*) from parties");
        owner.post("/api/v1/leads/" + lead + "/convert", CREATE_BOTH).andExpect(status().isConflict())
                .andExpect(jsonPath("$.party").value("organization"))
                .andExpect(jsonPath("$.duplicates[0].id").value(existing.toString()));
        assertThat(count("select count(*) from parties")).isEqualTo(partiesBefore);
        owner.get("/api/v1/leads/" + lead).andExpect(jsonPath("$.status").value("NEW"));

        owner.post("/api/v1/leads/" + lead + "/convert", "{\"person\":{\"firstName\":\"Grace\"},\"organization\":"
                + "{\"existingId\":\"" + existing + "\"},\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.convertedOrganization.id").value(existing.toString()))
                .andExpect(jsonPath("$.convertedOpportunityId").doesNotExist());
    }

    @Test
    void aReasonCreatesTheDuplicateAnyway() throws Exception {
        Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Acme Robotics\"}"));
        owner.post("/api/v1/leads/" + lead + "/convert", "{\"organization\":{\"name\":\"Acme Robotics\","
                + "\"duplicateReason\":\"Separate Pune branch\"},\"version\":0}").andExpect(status().isOk());
        assertThat(count("select count(*) from parties where duplicate_reason = 'Separate Pune branch'")).isEqualTo(1);
    }

    @Test
    void aDuplicatePersonRollsBackTheNewOrganization() throws Exception {
        Api.id(owner.post("/api/v1/persons", "{\"firstName\":\"G\",\"email\":\"grace@acme.test\"}"));
        long organizations = count("select count(*) from parties where kind = 'ORGANIZATION'");
        owner.post("/api/v1/leads/" + lead + "/convert", CREATE_BOTH).andExpect(status().isConflict())
                .andExpect(jsonPath("$.party").value("person"));
        assertThat(count("select count(*) from parties where kind = 'ORGANIZATION'")).isEqualTo(organizations);
        assertThat(count("select count(*) from opportunities")).isZero();
    }

    @Test
    void aCompanyOnlyConversionMakesTheOrganizationTheCustomer() throws Exception {
        UUID company = Api.id(owner.post("/api/v1/leads", "{\"companyName\":\"Deccan Spices\",\"email\":\"hi@deccan.test\"}"));
        owner.post("/api/v1/leads/" + company + "/convert", "{\"organization\":{\"name\":\"Deccan Spices\","
                + "\"email\":\"hi@deccan.test\"},\"opportunity\":{\"name\":\"Spice supply\",\"amount\":900,"
                + "\"currency\":\"INR\"},\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.convertedPerson").doesNotExist());
        owner.get("/api/v1/opportunities?q=spice").andExpect(jsonPath("$.items[0].contact").doesNotExist())
                .andExpect(jsonPath("$.items[0].currency").value("INR"));
    }

    @Test
    void invalidConversionsAreRefused() throws Exception {
        UUID org = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Other\"}"));
        List<String> stages = Api.read(owner.get("/api/v1/crm/pipeline/stages"), "$[*].id");
        String[][] badRequests = {
                {"{\"version\":0}", "person"},
                {"{\"person\":{\"existingId\":\"" + org + "\"},\"version\":0}", "person.existingId"},
                {"{\"person\":{\"existingId\":\"" + UUID.randomUUID() + "\"},\"version\":0}", "person.existingId"},
                {"{\"person\":{\"lastName\":\"Only\"},\"version\":0}", "person.firstName"},
                {"{\"organization\":{\"name\":\"X\",\"domain\":\"not a domain\"},\"version\":0}", "organization.domain"},
                {"{\"organization\":{\"existingId\":\"" + org + "\"},\"opportunity\":{\"stageId\":\"" + stages.get(4)
                        + "\"},\"version\":0}", "opportunity.stageId"},
                {"{\"organization\":{\"existingId\":\"" + org + "\"}}", "version"},
        };
        for (String[] c : badRequests) {
            owner.post("/api/v1/leads/" + lead + "/convert", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
        owner.post("/api/v1/leads/" + lead + "/convert", "{\"organization\":{\"existingId\":\"" + org + "\"},\"version\":5}")
                .andExpect(status().isConflict());
        owner.post("/api/v1/leads/" + lead + "/status", "{\"status\":\"DISQUALIFIED\",\"reason\":\"No\",\"version\":0}")
                .andExpect(status().isOk());
        owner.post("/api/v1/leads/" + lead + "/convert", "{\"organization\":{\"existingId\":\"" + org + "\"},\"version\":1}")
                .andExpect(status().isConflict());
        assertThat(count("select count(*) from leads where status = 'CONVERTED'")).isZero();
    }

    @Test
    void conversionNeedsTheDirectoryAndOpportunityPermissionsItUses() throws Exception {
        UUID org = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Linked Co\"}"));
        UUID role = TestRoles.create(mvc, owner.session(), "Lead converter", "crm.lead.read", "crm.lead.manage",
                "directory.party.read");
        Api converter = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        converter.post("/api/v1/leads/" + lead + "/convert", CREATE_BOTH).andExpect(status().isForbidden());
        converter.post("/api/v1/leads/" + lead + "/convert", "{\"organization\":{\"existingId\":\"" + org
                + "\"},\"opportunity\":{},\"version\":0}").andExpect(status().isForbidden());
        converter.post("/api/v1/leads/" + lead + "/convert", "{\"organization\":{\"existingId\":\"" + org
                + "\"},\"version\":0}").andExpect(status().isOk());
        // linking still makes the account a customer: a CRM business rule, not the converter's directory permission
        owner.get("/api/v1/parties/" + org).andExpect(jsonPath("$.roles[?(@.role == 'CUSTOMER')].status")
                .value(Matchers.contains("ACTIVE")));
    }
}
