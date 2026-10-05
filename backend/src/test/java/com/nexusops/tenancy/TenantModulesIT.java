package com.nexusops.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Session;
import com.nexusops.support.TestTenants.Workspace;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class TenantModulesIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    Workspace ws;
    Session owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("mods"));
        owner = TestTenants.login(mvc, ws);
    }

    private ResultActions setModule(String code, boolean enabled) throws Exception {
        return mvc.perform(put("/api/v1/tenant/modules/" + code).header("Authorization", "Bearer " + owner.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":" + enabled + "}"));
    }

    private ResultActions me() throws Exception {
        return mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + owner.accessToken()));
    }

    @Test
    void listsTheCatalogAllDisabledForANewWorkspace() throws Exception {
        mvc.perform(get("/api/v1/tenant/modules").header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", Matchers.contains("CRM", "HELPDESK", "HRMS", "INVENTORY")))
                .andExpect(jsonPath("$[*].enabled", Matchers.everyItem(Matchers.is(false))));
    }

    @Test
    void enablingAModuleTakesEffectImmediatelyForPermissions() throws Exception {
        me().andExpect(jsonPath("$.permissions", Matchers.not(Matchers.hasItem("crm.customer.read"))));
        setModule("CRM", true).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        me().andExpect(jsonPath("$.modules", Matchers.contains("CRM")))
                .andExpect(jsonPath("$.permissions", Matchers.hasItem("crm.customer.read")));
        setModule("CRM", false).andExpect(status().isOk());
        me().andExpect(jsonPath("$.permissions", Matchers.not(Matchers.hasItem("crm.customer.read"))));
    }

    @Test
    void freePlanAllowsTwoModules() throws Exception {
        setModule("CRM", true).andExpect(status().isOk());
        setModule("HELPDESK", true).andExpect(status().isOk());
        setModule("HRMS", true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Your plan allows 2 modules. Upgrade to enable more."));
        setModule("CRM", false).andExpect(status().isOk());
        setModule("HRMS", true).andExpect(status().isOk());
    }

    @Test
    void unlimitedPlansHaveNoModuleCap() throws Exception {
        OwnerJdbc.jdbc().update("update tenants set plan_code = 'ENTERPRISE' where id = ?", ws.tenantId());
        for (String code : new String[] {"CRM", "HELPDESK", "HRMS", "INVENTORY"}) {
            setModule(code, true).andExpect(status().isOk());
        }
    }

    @Test
    void unknownModuleIs404AndRepeatedTogglesAuditOnce() throws Exception {
        setModule("NOPE", true).andExpect(status().isNotFound());
        setModule("CRM", true).andExpect(status().isOk());
        setModule("CRM", true).andExpect(status().isOk());
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = 'ModuleEnabled'", Long.class)).isOne();
    }
}
