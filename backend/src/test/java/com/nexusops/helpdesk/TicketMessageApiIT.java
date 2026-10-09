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
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class TicketMessageApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    String customerEmail;
    UUID customer;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("msg"));
        owner = Api.login(mvc, ws);
        TestHelpDesk.enable(owner);
        customerEmail = "meera-" + ws.slug() + "@sahyadri.test";
        customer = Api.id(owner.post("/api/v1/persons", "{\"firstName\":\"Meera\",\"lastName\":\"Iyer\",\"email\":\""
                + customerEmail + "\"}"));
    }

    private UUID ticket(UUID requester) throws Exception {
        return Api.id(owner.post("/api/v1/helpdesk/tickets", "{\"subject\":\"Printer not printing\","
                + "\"description\":\"Paper jams\",\"requesterId\":\"" + requester + "\"}").andExpect(status().isCreated()));
    }

    private ResultActions post(UUID ticket, String kind, String body) throws Exception {
        return owner.post("/api/v1/helpdesk/tickets/" + ticket + "/messages",
                "{\"kind\":\"" + kind + "\",\"body\":\"" + body + "\"}");
    }

    private long audits(String action) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = ?", Long.class, action);
    }

    @Test
    void aPublicReplyEmailsTheRequesterAndIsTheFirstResponse() throws Exception {
        UUID id = ticket(customer);
        post(id, "PUBLIC_REPLY", "Please switch it off and on again.").andExpect(status().isCreated())
                .andExpect(jsonPath("$.message.kind").value("PUBLIC_REPLY"))
                .andExpect(jsonPath("$.message.emailedTo").value(customerEmail))
                .andExpect(jsonPath("$.message.author.name").exists())
                .andExpect(jsonPath("$.ticket.status").value("OPEN"))
                .andExpect(jsonPath("$.ticket.sla.firstRespondedAt").exists())
                .andExpect(jsonPath("$.ticket.sla.firstResponseState").value("MET"));
        assertThat(mail.sentTo(customerEmail)).singleElement().satisfies(m -> {
            assertThat(m.subject()).isEqualTo("[T-00001] Printer not printing");
            assertThat(m.textBody()).contains("Please switch it off and on again.");
        });
        String firstAt = Api.read(owner.get("/api/v1/helpdesk/tickets/" + id), "$.sla.firstRespondedAt");
        post(id, "PUBLIC_REPLY", "Any luck?").andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticket.sla.firstRespondedAt").value(firstAt));
        assertThat(audits("TicketReplied")).isEqualTo(2);
    }

    @Test
    void internalNotesAreNotEmailedAndDontCountAsAResponse() throws Exception {
        UUID id = ticket(customer);
        post(id, "INTERNAL_NOTE", "Customer is on the old firmware").andExpect(status().isCreated())
                .andExpect(jsonPath("$.message.emailedTo").doesNotExist())
                .andExpect(jsonPath("$.ticket.status").value("NEW"))
                .andExpect(jsonPath("$.ticket.sla.firstRespondedAt").doesNotExist());
        assertThat(mail.sentTo(customerEmail)).isEmpty();
        assertThat(audits("TicketNoteAdded")).isEqualTo(1);
    }

    @Test
    void aCustomerMessageResumesAPendingTicket() throws Exception {
        UUID id = ticket(customer);
        owner.post("/api/v1/helpdesk/tickets/" + id + "/status", "{\"status\":\"PENDING\",\"version\":0}")
                .andExpect(status().isOk());
        post(id, "CUSTOMER_MESSAGE", "It works after the update, but it is slow").andExpect(status().isCreated())
                .andExpect(jsonPath("$.message.kind").value("CUSTOMER_MESSAGE"))
                .andExpect(jsonPath("$.ticket.status").value("OPEN"))
                .andExpect(jsonPath("$.ticket.sla.pausedAt").doesNotExist())
                .andExpect(jsonPath("$.ticket.sla.firstRespondedAt").doesNotExist());
        assertThat(mail.sentTo(customerEmail)).isEmpty();
        assertThat(audits("TicketCustomerMessage")).isEqualTo(1);
    }

    @Test
    void aReplyToARequesterWithoutEmailIsStillRecorded() throws Exception {
        UUID shop = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Walk-in Traders\"}"));
        UUID id = ticket(shop);
        post(id, "PUBLIC_REPLY", "Bring it to the counter on Monday").andExpect(status().isCreated())
                .andExpect(jsonPath("$.message.emailedTo").doesNotExist())
                .andExpect(jsonPath("$.ticket.sla.firstRespondedAt").exists())
                .andExpect(jsonPath("$.ticket.status").value("OPEN"));
    }

    @Test
    void anOrganisationsOwnEmailReceivesTheReply() throws Exception {
        UUID shop = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Deccan Retail\",\"email\":\"care-"
                + ws.slug() + "@deccan.test\"}"));
        UUID id = ticket(shop);
        post(id, "PUBLIC_REPLY", "Replacement shipped").andExpect(jsonPath("$.message.emailedTo")
                .value("care-" + ws.slug() + "@deccan.test"));
    }

    @Test
    void theConversationReadsOldestFirstAndRefusesBadInput() throws Exception {
        UUID id = ticket(customer);
        post(id, "INTERNAL_NOTE", "First").andExpect(status().isCreated());
        post(id, "PUBLIC_REPLY", "Second").andExpect(status().isCreated());
        owner.get("/api/v1/helpdesk/tickets/" + id + "/messages")
                .andExpect(jsonPath("$[*].body").value(Matchers.contains("First", "Second")));
        post(id, "PUBLIC_REPLY", "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("body"));
        owner.post("/api/v1/helpdesk/tickets/" + id + "/messages", "{\"body\":\"x\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("kind"));
        owner.post("/api/v1/helpdesk/tickets/" + UUID.randomUUID() + "/messages",
                "{\"kind\":\"PUBLIC_REPLY\",\"body\":\"x\"}").andExpect(status().isNotFound());
    }

    @Test
    void aClosedTicketTakesNoMessagesAndReadersCantWrite() throws Exception {
        UUID id = ticket(customer);
        owner.post("/api/v1/helpdesk/tickets/" + id + "/status", "{\"status\":\"RESOLVED\",\"note\":\"x\",\"version\":0}");
        owner.post("/api/v1/helpdesk/tickets/" + id + "/status", "{\"status\":\"CLOSED\",\"version\":1}");
        post(id, "PUBLIC_REPLY", "Late").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A closed ticket can't be changed."));
        UUID readerRole = TestRoles.create(mvc, owner.session(), "Viewer", "helpdesk.ticket.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(readerRole)));
        reader.get("/api/v1/helpdesk/tickets/" + id + "/messages").andExpect(status().isOk());
        reader.post("/api/v1/helpdesk/tickets/" + id + "/messages", "{\"kind\":\"INTERNAL_NOTE\",\"body\":\"x\"}")
                .andExpect(status().isForbidden());
    }
}
