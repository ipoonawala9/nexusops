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
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class PartyRolesIT extends IntegrationTestSupport {

    static final String FORBIDDEN = "You do not have permission to perform this action.";

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID acme;
    UUID ada;

    @BeforeEach
    void directory() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("proles"));
        owner = Api.login(mvc, ws);
        acme = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Acme\"}"));
        ada = Api.id(owner.post("/api/v1/persons", "{\"firstName\":\"Ada\"}"));
    }

    private Api memberWith(String... permissions) throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "R" + UUID.randomUUID().toString().substring(0, 6), permissions);
        return Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
    }

    @Test
    void oneOrganizationCanBeBothCustomerAndSupplier() throws Exception {
        owner.put("/api/v1/parties/" + acme + "/roles/CUSTOMER", "{\"since\":\"2026-01-15\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0].role").value("CUSTOMER"))
                .andExpect(jsonPath("$.roles[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.roles[0].since").value("2026-01-15"));
        owner.put("/api/v1/parties/" + acme + "/roles/SUPPLIER", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[*].role", Matchers.contains("CUSTOMER", "SUPPLIER")));
        owner.get("/api/v1/parties?role=CUSTOMER").andExpect(jsonPath("$.items[*].id", Matchers.contains(acme.toString())))
                .andExpect(jsonPath("$.items[0].roles", Matchers.contains("CUSTOMER", "SUPPLIER")));
        Long count = OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from parties where kind = 'ORGANIZATION'", Long.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void endingARoleKeepsItAsInactiveAndDropsItFromRoleFilters() throws Exception {
        owner.put("/api/v1/parties/" + acme + "/roles/CUSTOMER", "{}");
        owner.put("/api/v1/parties/" + acme + "/roles/CUSTOMER", "{\"status\":\"INACTIVE\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0].status").value("INACTIVE"));
        owner.get("/api/v1/parties?role=CUSTOMER").andExpect(jsonPath("$.total").value(0));
        Long audited = OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = 'PartyRoleChanged' and entity_id = ?", Long.class,
                acme.toString());
        assertThat(audited).isEqualTo(2);
    }

    @Test
    void onlyPeopleCanBeEmployeesAndEmployeeNumbersAreUnique() throws Exception {
        owner.put("/api/v1/parties/" + acme + "/roles/EMPLOYEE", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("role"));
        owner.put("/api/v1/parties/" + ada + "/roles/EMPLOYEE", "{\"employeeNumber\":\"E-1\",\"since\":\"2025-03-01\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles[0].employeeNumber").value("E-1"));
        UUID bob = Api.id(owner.post("/api/v1/persons", "{\"firstName\":\"Bob\"}"));
        owner.put("/api/v1/parties/" + bob + "/roles/EMPLOYEE", "{\"employeeNumber\":\"e-1\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errors[0].field").value("employeeNumber"));
        owner.put("/api/v1/parties/" + bob + "/roles/CUSTOMER", "{\"employeeNumber\":\"E-2\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("employeeNumber"));
        // re-saving your own number is fine
        owner.put("/api/v1/parties/" + ada + "/roles/EMPLOYEE", "{\"employeeNumber\":\"E-1\",\"status\":\"INACTIVE\"}")
                .andExpect(status().isOk());
    }

    @Test
    void employeeRecordsNeedTheEmployeePermissions() throws Exception {
        owner.put("/api/v1/parties/" + ada + "/roles/EMPLOYEE", "{}").andExpect(status().isOk());
        owner.put("/api/v1/parties/" + ada + "/roles/CUSTOMER", "{}").andExpect(status().isOk());

        Api directoryOnly = memberWith("directory.party.read", "directory.party.manage");
        directoryOnly.get("/api/v1/parties/" + ada)
                .andExpect(jsonPath("$.roles[*].role", Matchers.contains("CUSTOMER")));
        directoryOnly.get("/api/v1/parties").andExpect(jsonPath("$.items[?(@.name == 'Ada')].roles[*]",
                Matchers.contains("CUSTOMER")));
        directoryOnly.get("/api/v1/parties?role=EMPLOYEE").andExpect(status().isForbidden());
        directoryOnly.put("/api/v1/parties/" + ada + "/roles/EMPLOYEE", "{}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(FORBIDDEN));
        directoryOnly.put("/api/v1/parties/" + acme + "/roles/SUPPLIER", "{}").andExpect(status().isOk());

        Api hr = memberWith("directory.party.read", "directory.employee.read", "directory.employee.manage");
        hr.get("/api/v1/parties?role=EMPLOYEE").andExpect(jsonPath("$.items[*].id", Matchers.contains(ada.toString())));
        hr.put("/api/v1/parties/" + ada + "/roles/EMPLOYEE", "{\"employeeNumber\":\"E-9\"}").andExpect(status().isOk());
        hr.put("/api/v1/parties/" + ada + "/roles/CUSTOMER", "{\"status\":\"INACTIVE\"}").andExpect(status().isForbidden());
    }

    @Test
    void archivedPartiesTakeNoRoleChangesAndUnknownRolesAre404() throws Exception {
        owner.post("/api/v1/parties/" + acme + "/archive", "");
        owner.put("/api/v1/parties/" + acme + "/roles/CUSTOMER", "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This record is archived."));
        owner.put("/api/v1/parties/" + ada + "/roles/PARTNER", "{}").andExpect(status().isNotFound());
        owner.put("/api/v1/parties/" + UUID.randomUUID() + "/roles/CUSTOMER", "{}").andExpect(status().isNotFound());
    }
}
