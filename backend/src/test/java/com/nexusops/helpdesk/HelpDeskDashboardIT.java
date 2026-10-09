package com.nexusops.helpdesk;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHelpDesk;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class HelpDeskDashboardIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    Workspace ws;
    Api owner;
    UUID customer;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hddash"));
        owner = Api.login(mvc, ws);
        TestHelpDesk.enable(owner);
        customer = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Deccan Retail\"}"));
    }

    private UUID ticket(String priority) throws Exception {
        return Api.id(owner.post("/api/v1/helpdesk/tickets", "{\"subject\":\"S\",\"description\":\"D\",\"requesterId\":\""
                + customer + "\",\"priority\":\"" + priority + "\"}").andExpect(status().isCreated()));
    }

    private JdbcTemplate jdbc() {
        return OwnerJdbc.ownerAs(ws.tenantId());
    }

    @Test
    void anEmptyHelpDeskHasZerosAndNoRates() throws Exception {
        owner.get("/api/v1/helpdesk/dashboard").andExpect(status().isOk())
                .andExpect(jsonPath("$.openByStatus.NEW").value(0))
                .andExpect(jsonPath("$.unassigned").value(0))
                .andExpect(jsonPath("$.last30Days.created").value(0))
                .andExpect(jsonPath("$.last30Days.averageFirstResponseMinutes").doesNotExist())
                .andExpect(jsonPath("$.last30Days.firstResponseMetRate").doesNotExist())
                .andExpect(jsonPath("$.last30Days.reopenRate").doesNotExist());
    }

    @Test
    void summarisesOpenWorkAndTheLastThirtyDays() throws Exception {
        UUID fast = ticket("NORMAL");
        UUID slow = ticket("HIGH");
        UUID pending = ticket("URGENT");
        ticket("LOW");
        // fast: answered after 30 min, resolved after 2 h, within targets
        jdbc().update("update tickets set status = 'RESOLVED', first_responded_at = created_at + interval '30 minutes', "
                + "resolved_at = created_at + interval '2 hours', resolution_note = 'x' where id = ?", fast);
        // slow: answered after 10 h (HIGH target 4 h: breached), reopened once, open again
        jdbc().update("update tickets set status = 'OPEN', first_responded_at = created_at + interval '10 hours', "
                + "reopen_count = 1 where id = ?", slow);
        jdbc().update("update tickets set status = 'PENDING', paused_at = now() where id = ?", pending);
        owner.get("/api/v1/helpdesk/dashboard")
                .andExpect(jsonPath("$.openByStatus.NEW").value(1))
                .andExpect(jsonPath("$.openByStatus.OPEN").value(1))
                .andExpect(jsonPath("$.openByStatus.PENDING").value(1))
                .andExpect(jsonPath("$.openByPriority.HIGH").value(1))
                .andExpect(jsonPath("$.openByPriority.NORMAL").value(0))
                .andExpect(jsonPath("$.unassigned").value(3))
                .andExpect(jsonPath("$.breached").value(1)) // slow: open, its first response came late
                .andExpect(jsonPath("$.atRisk").value(0))
                .andExpect(jsonPath("$.last30Days.created").value(4))
                .andExpect(jsonPath("$.last30Days.resolved").value(1))
                .andExpect(jsonPath("$.last30Days.averageFirstResponseMinutes").value(315.0))
                .andExpect(jsonPath("$.last30Days.medianFirstResponseMinutes").value(315.0))
                .andExpect(jsonPath("$.last30Days.averageResolutionMinutes").value(120.0))
                .andExpect(jsonPath("$.last30Days.firstResponseMetRate").value(0.5))
                .andExpect(jsonPath("$.last30Days.resolutionMetRate").value(1.0))
                .andExpect(jsonPath("$.last30Days.reopenRate").value(0.5));
    }

    @Test
    void anOverdueUnansweredTicketCountsAsAMissedFirstResponse() throws Exception {
        UUID answered = ticket("NORMAL");
        UUID overdue = ticket("NORMAL");
        jdbc().update("update tickets set status = 'OPEN', first_responded_at = created_at + interval '30 minutes' "
                + "where id = ?", answered);
        jdbc().update("update tickets set first_response_due_at = now() - interval '1 hour' where id = ?", overdue);
        owner.get("/api/v1/helpdesk/dashboard")
                .andExpect(jsonPath("$.last30Days.firstResponseMetRate").value(0.5))
                .andExpect(jsonPath("$.last30Days.averageFirstResponseMinutes").value(30.0));
    }

    @Test
    void anOverdueUnresolvedTicketCountsAsAMissedResolution() throws Exception {
        UUID resolved = ticket("NORMAL");
        UUID overdue = ticket("NORMAL");
        jdbc().update("update tickets set status = 'RESOLVED', first_responded_at = created_at, "
                + "resolved_at = created_at + interval '1 hour', resolution_note = 'x' where id = ?", resolved);
        jdbc().update("update tickets set resolution_due_at = now() - interval '1 hour' where id = ?", overdue);
        owner.get("/api/v1/helpdesk/dashboard")
                .andExpect(jsonPath("$.last30Days.resolutionMetRate").value(0.5));
    }
}
