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
class ArticleApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("kb"));
        owner = Api.login(mvc, ws);
        TestHelpDesk.enable(owner);
    }

    private UUID article(String title, String body) throws Exception {
        return Api.id(owner.post("/api/v1/helpdesk/articles", "{\"title\":\"" + title + "\",\"body\":\"" + body + "\"}")
                .andExpect(status().isCreated()));
    }

    private long audits(String action) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = ?", Long.class, action);
    }

    @Test
    void writesPublishesUnpublishesAndArchives() throws Exception {
        UUID product = TestHelpDesk.category(owner, "Product issue");
        UUID id = Api.id(owner.post("/api/v1/helpdesk/articles", "{\"title\":\"Clearing a paper jam\","
                        + "\"body\":\"Open the rear tray.\\nPull the paper out slowly.\",\"categoryId\":\"" + product + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.category.name").value("Product issue"))
                .andExpect(jsonPath("$.author.name").exists())
                .andExpect(jsonPath("$.publishedAt").doesNotExist()));
        owner.post("/api/v1/helpdesk/articles/" + id + "/publish", "{\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED")).andExpect(jsonPath("$.publishedAt").exists());
        owner.put("/api/v1/helpdesk/articles/" + id, "{\"title\":\"Clearing paper jams\",\"body\":\"Open the rear tray.\","
                + "\"version\":1}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.category").doesNotExist());
        owner.post("/api/v1/helpdesk/articles/" + id + "/unpublish", "{\"version\":2}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
        owner.post("/api/v1/helpdesk/articles/" + id + "/archive", "{\"version\":3}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        owner.post("/api/v1/helpdesk/articles/" + id + "/publish", "{\"version\":4}").andExpect(status().isConflict());
        owner.put("/api/v1/helpdesk/articles/" + id, "{\"title\":\"x\",\"body\":\"y\",\"version\":4}")
                .andExpect(status().isConflict());
        assertThat(audits("ArticleCreated")).isEqualTo(1);
        assertThat(audits("ArticlePublished")).isEqualTo(1);
        assertThat(audits("ArticleUpdated")).isEqualTo(1);
        assertThat(audits("ArticleUnpublished")).isEqualTo(1);
        assertThat(audits("ArticleArchived")).isEqualTo(1);
    }

    @Test
    void invalidArticlesAreFieldErrors() throws Exception {
        String[][] cases = {
                {"{\"body\":\"x\"}", "title"},
                {"{\"title\":\"" + "x".repeat(201) + "\",\"body\":\"x\"}", "title"},
                {"{\"title\":\"x\"}", "body"},
                {"{\"title\":\"x\",\"body\":\"x\",\"categoryId\":\"" + UUID.randomUUID() + "\"}", "categoryId"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/helpdesk/articles", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
    }

    @Test
    void searchesTitleAndBodyRankingTitlesFirst() throws Exception {
        UUID inBody = article("Toner care", "A paper jam can come from old toner.");
        UUID inTitle = article("Paper jam in the rear tray", "Open the tray.");
        UUID unrelated = article("Refund policy", "Refunds take five days.");
        for (UUID id : new UUID[] {inBody, inTitle, unrelated}) {
            owner.post("/api/v1/helpdesk/articles/" + id + "/publish", "{\"version\":0}").andExpect(status().isOk());
        }
        owner.get("/api/v1/helpdesk/articles?q=paper jam").andExpect(jsonPath("$.items[*].id")
                .value(Matchers.contains(inTitle.toString(), inBody.toString())));
        owner.get("/api/v1/helpdesk/articles?q=\"rear tray\" OR refunds").andExpect(jsonPath("$.total").value(2));
        owner.get("/api/v1/helpdesk/articles?q=!!! ' :*").andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        owner.get("/api/v1/helpdesk/articles").andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].excerpt").exists());
    }

    @Test
    void readersSeeOnlyPublishedArticles() throws Exception {
        UUID draft = article("Internal draft", "Not ready");
        UUID published = article("How to reset", "Hold the button.");
        owner.post("/api/v1/helpdesk/articles/" + published + "/publish", "{\"version\":0}").andExpect(status().isOk());
        UUID readerRole = TestRoles.create(mvc, owner.session(), "KB reader", "helpdesk.article.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(readerRole)));
        reader.get("/api/v1/helpdesk/articles").andExpect(jsonPath("$.items[*].id")
                .value(Matchers.contains(published.toString())));
        reader.get("/api/v1/helpdesk/articles?status=DRAFT").andExpect(jsonPath("$.total").value(0));
        reader.get("/api/v1/helpdesk/articles/" + draft).andExpect(status().isNotFound());
        reader.get("/api/v1/helpdesk/articles/" + published).andExpect(status().isOk());
        reader.post("/api/v1/helpdesk/articles", "{\"title\":\"x\",\"body\":\"y\"}").andExpect(status().isForbidden());
        owner.get("/api/v1/helpdesk/articles?status=DRAFT").andExpect(jsonPath("$.items[*].id")
                .value(Matchers.contains(draft.toString())));
    }

    @Test
    void draftAndArchivedArticlesAreNotSubjectsForReadersWithoutManage() throws Exception {
        UUID draft = article("Internal draft", "Not ready");
        UUID readerRole = TestRoles.create(mvc, owner.session(), "KB reader", "helpdesk.article.read",
                "collaboration.activity.create");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(readerRole)));
        reader.get("/api/v1/activities?subjectType=KB_ARTICLE&subjectId=" + draft).andExpect(status().isNotFound());
        owner.get("/api/v1/activities?subjectType=KB_ARTICLE&subjectId=" + draft).andExpect(status().isOk());
        owner.post("/api/v1/helpdesk/articles/" + draft + "/publish", "{\"version\":0}").andExpect(status().isOk());
        reader.get("/api/v1/activities?subjectType=KB_ARTICLE&subjectId=" + draft).andExpect(status().isOk());
        owner.post("/api/v1/helpdesk/articles/" + draft + "/archive", "{\"version\":1}").andExpect(status().isOk());
        reader.get("/api/v1/activities?subjectType=KB_ARTICLE&subjectId=" + draft).andExpect(status().isNotFound());
    }

    @Test
    void anArchivedArticleStillGetsBodyErrorsBeforeTheConflict() throws Exception {
        UUID id = article("Old", "Old body");
        owner.post("/api/v1/helpdesk/articles/" + id + "/archive", "{\"version\":0}").andExpect(status().isOk());
        owner.put("/api/v1/helpdesk/articles/" + id, "{\"title\":\"\",\"body\":\"y\",\"version\":1}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("title"));
    }
}
