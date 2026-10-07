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
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@AutoConfigureMockMvc
class LeadImportIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("imp"));
        owner = Api.login(mvc, ws);
        TestCrm.enable(owner);
    }

    private ResultActions upload(Api api, String csv) throws Exception {
        return api.perform(MockMvcRequestBuilders.multipart("/api/v1/leads/import").file(
                new MockMultipartFile("file", "leads.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8))));
    }

    private long leads() {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select count(*) from leads", Long.class);
    }

    @Test
    void importsAnExcelStyleFile() throws Exception {
        String csv = "﻿first_name,last_name,company,email,source,estimated_value,currency\r\n"
                + "Grace,Hopper,\"Acme, Inc\",GRACE@acme.test,referral,1200.50,\r\n"
                + ",,Deccan Spices,,,900,inr\r\n"
                + "\r\n";
        upload(owner, csv).andExpect(status().isOk()).andExpect(jsonPath("$.imported").value(2));
        owner.get("/api/v1/leads?q=acme").andExpect(jsonPath("$.items[0].companyName").value("Acme, Inc"))
                .andExpect(jsonPath("$.items[0].email").value("grace@acme.test"))
                .andExpect(jsonPath("$.items[0].source").value("REFERRAL"))
                .andExpect(jsonPath("$.items[0].currency").value("USD"))
                .andExpect(jsonPath("$.items[0].owner").exists());
        owner.get("/api/v1/leads?q=deccan").andExpect(jsonPath("$.items[0].name").value("Deccan Spices"))
                .andExpect(jsonPath("$.items[0].currency").value("INR"))
                .andExpect(jsonPath("$.items[0].source").value("OTHER"));
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select metadata ->> 'count' from audit_events where action = 'LeadsImported'", String.class)).isEqualTo("2");
    }

    @Test
    void importsAFileWithTrailingEmptyColumns() throws Exception {
        upload(owner, "company,email,,\r\nAcme,a@acme.test,,\r\n").andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1));
    }

    @Test
    void anyInvalidRowRejectsTheWholeFile() throws Exception {
        String csv = "first_name,company,email,estimated_value,source\n"
                + "Grace,Acme,grace@acme.test,10,WEBSITE\n"
                + "Bad,Co,not-an-email,10,WEBSITE\n"
                + "Neg,Co,,-5,WEBSITE\n"
                + ",,,,\n"
                + "Src,Co,,,BILLBOARD\n"
                + "Num,Co,,lots,WEBSITE\n";
        upload(owner, csv).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCount").value(4))
                .andExpect(jsonPath("$.rows[*].row").value(Matchers.contains(3, 4, 6, 7)))
                .andExpect(jsonPath("$.rows[*].field").value(Matchers.contains("email", "estimated_value", "source",
                        "estimated_value")));
        assertThat(leads()).isZero();
    }

    @Test
    void aRowWithoutAnyNameIsAnError() throws Exception {
        upload(owner, "first_name,company,email\nGrace,,\n,,x@y.test\n").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.rows[0].row").value(3)).andExpect(jsonPath("$.rows[0].field").value("last_name"));
    }

    @Test
    void fileLevelProblemsAreFieldErrors() throws Exception {
        upload(owner, "company,colour\nA,red\n").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("file"));
        upload(owner, "company\n").andExpect(status().isBadRequest());
        owner.perform(MockMvcRequestBuilders.multipart("/api/v1/leads/import")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("file"));
    }

    @Test
    void readersCannotImport() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "Lead reader", "crm.lead.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        upload(reader, "company\nAcme\n").andExpect(status().isForbidden());
        assertThat(leads()).isZero();
    }
}
