package com.nexusops.helpdesk;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
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
class TicketContextIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID meera, arjun;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("ctx"));
        owner = Api.login(mvc, ws);
        TestHelpDesk.enable(owner);
        meera = Api.id(owner.post("/api/v1/persons", "{\"firstName\":\"Meera\",\"lastName\":\"Iyer\"}"));
        arjun = Api.id(owner.post("/api/v1/persons", "{\"firstName\":\"Arjun\",\"lastName\":\"Patil\"}"));
    }

    private UUID ticket(UUID requester, String subject, String description) throws Exception {
        return Api.id(owner.post("/api/v1/helpdesk/tickets", "{\"subject\":\"" + subject + "\",\"description\":\""
                + description + "\",\"requesterId\":\"" + requester + "\"}").andExpect(status().isCreated()));
    }

    private UUID published(String title, String body) throws Exception {
        UUID id = Api.id(owner.post("/api/v1/helpdesk/articles", "{\"title\":\"" + title + "\",\"body\":\"" + body + "\"}"));
        owner.post("/api/v1/helpdesk/articles/" + id + "/publish", "{\"version\":0}").andExpect(status().isOk());
        return id;
    }

    @Test
    void showsTheRequestersOtherTicketsDuplicatesAndArticles() throws Exception {
        UUID older = ticket(meera, "Invoice has the wrong GST number", "Please correct it");
        UUID duplicate = ticket(meera, "Printer jams on every page", "Since yesterday");
        UUID otherCustomers = ticket(arjun, "Printer jams too", "Same here");
        UUID article = published("Clearing a paper jam", "Open the rear tray and remove the paper.");
        published("Refund policy", "Refunds take five days.");
        UUID current = ticket(meera, "Printer jams with paper", "Paper is stuck in the rear tray");
        owner.get("/api/v1/helpdesk/tickets/" + current + "/context").andExpect(status().isOk())
                .andExpect(jsonPath("$.previousTickets[*].id")
                        .value(Matchers.contains(duplicate.toString(), older.toString())))
                .andExpect(jsonPath("$.possibleDuplicates[*].id").value(Matchers.contains(duplicate.toString())))
                .andExpect(jsonPath("$.suggestedArticles[*].id").value(Matchers.contains(article.toString())));
        // a resolved ticket is history, not a duplicate
        owner.post("/api/v1/helpdesk/tickets/" + duplicate + "/status", "{\"status\":\"RESOLVED\",\"note\":\"x\",\"version\":0}")
                .andExpect(status().isOk());
        owner.get("/api/v1/helpdesk/tickets/" + current + "/context")
                .andExpect(jsonPath("$.possibleDuplicates").isEmpty())
                .andExpect(jsonPath("$.previousTickets[*].id", Matchers.not(Matchers.hasItem(otherCustomers.toString()))));
    }

    @Test
    void punctuationAndOtherScriptsDoNotBreakMatching() throws Exception {
        UUID article = published("Error 0x80 when printing", "Reinstall the driver.");
        UUID weird = ticket(meera, "can't print: error 0x80!! a & b | c <-> (d)", "':* !");
        owner.get("/api/v1/helpdesk/tickets/" + weird + "/context").andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedArticles[*].id").value(Matchers.contains(article.toString())));
        UUID hindi = ticket(meera, "प्रिंटर काम नहीं कर रहा", "कागज़ फँस गया");
        owner.get("/api/v1/helpdesk/tickets/" + hindi + "/context").andExpect(status().isOk());
        UUID nothing = ticket(arjun, "!!!", "???");
        owner.get("/api/v1/helpdesk/tickets/" + nothing + "/context").andExpect(status().isOk())
                .andExpect(jsonPath("$.possibleDuplicates").isEmpty())
                .andExpect(jsonPath("$.suggestedArticles").isEmpty());
    }

    @Test
    void articlesAreLeftOutForAgentsWhoCantReadThem() throws Exception {
        published("Clearing a paper jam", "Open the rear tray.");
        UUID current = ticket(meera, "Paper jam", "Stuck");
        UUID agentRole = TestRoles.create(mvc, owner.session(), "Ticket reader", "helpdesk.ticket.read");
        Api agent = Api.login(mvc, members.create(ws.tenantId(), Set.of(agentRole)));
        agent.get("/api/v1/helpdesk/tickets/" + current + "/context").andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedArticles").isEmpty());
        owner.get("/api/v1/helpdesk/tickets/" + UUID.randomUUID() + "/context").andExpect(status().isNotFound());
    }

    @Test
    void onlyPublishedArticlesAreSuggested() throws Exception {
        UUID draft = Api.id(owner.post("/api/v1/helpdesk/articles",
                "{\"title\":\"Paper jam draft\",\"body\":\"Draft steps.\"}"));
        UUID archived = published("Paper jam archived", "Old steps.");
        owner.post("/api/v1/helpdesk/articles/" + archived + "/archive", "{\"version\":1}").andExpect(status().isOk());
        UUID live = published("Paper jam live", "Current steps.");
        UUID current = ticket(meera, "Paper jam", "Stuck");
        owner.get("/api/v1/helpdesk/tickets/" + current + "/context").andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedArticles[*].id").value(Matchers.contains(live.toString())))
                .andExpect(jsonPath("$.suggestedArticles[*].id",
                        Matchers.not(Matchers.hasItems(draft.toString(), archived.toString()))));
    }
}
