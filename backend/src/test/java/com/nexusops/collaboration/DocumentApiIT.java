package com.nexusops.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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

@AutoConfigureMockMvc
class DocumentApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID acme;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("docs"));
        owner = Api.login(mvc, ws);
        acme = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Acme\"}"));
    }

    private ResultActions upload(Api api, UUID subject, String name, String type, byte[] bytes) throws Exception {
        return api.perform(multipart("/api/v1/documents").file(new MockMultipartFile("file", name, type, bytes))
                .param("subjectType", "PARTY").param("subjectId", subject.toString()));
    }

    private Api memberWith(String... permissions) throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "R" + UUID.randomUUID().toString().substring(0, 6), permissions);
        return Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
    }

    @Test
    void uploadsListsAndDownloadsWithSafeHeaders() throws Exception {
        byte[] bytes = "Signed contract".getBytes(StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        UUID id = Api.id(upload(owner, acme, "contract.txt", "text/plain", bytes).andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value("contract.txt"))
                .andExpect(jsonPath("$.contentType").value("text/plain"))
                .andExpect(jsonPath("$.sizeBytes").value(bytes.length))
                .andExpect(jsonPath("$.sha256").value(sha))
                .andExpect(jsonPath("$.uploadedBy.name").value("Ada Owner")));
        owner.get("/api/v1/documents?subjectType=PARTY&subjectId=" + acme)
                .andExpect(jsonPath("$[*].id", Matchers.contains(id.toString())));
        owner.get("/api/v1/documents/" + id + "/content").andExpect(status().isOk())
                .andExpect(content().bytes(bytes))
                .andExpect(header().string("Content-Type", Matchers.startsWith("text/plain")))
                .andExpect(header().string("Content-Disposition", Matchers.startsWith("attachment;")))
                .andExpect(header().string("Content-Disposition", Matchers.containsString("contract.txt")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")));
    }

    @Test
    void hostileNamesAndTypesAreNeutralized() throws Exception {
        UUID id = Api.id(upload(owner, acme, "../../evil\r\n.html", "text/html; charset=utf-8",
                "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.fileName").value("evil.html"))
                .andExpect(jsonPath("$.contentType").value("text/html")));
        owner.get("/api/v1/documents/" + id + "/content")
                .andExpect(header().string("Content-Disposition", Matchers.startsWith("attachment;")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        upload(owner, acme, "x.bin", "not a type", new byte[] {1}).andExpect(jsonPath("$.contentType")
                .value("application/octet-stream"));
    }

    @Test
    void emptyAndOversizedFilesAreRefused() throws Exception {
        upload(owner, acme, "empty.txt", "text/plain", new byte[0]).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("file"));
        upload(owner, acme, "big.bin", "application/octet-stream", new byte[10 * 1024 * 1024 + 1])
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.detail").value("The file is larger than 10 MB."));
        owner.perform(multipart("/api/v1/documents").param("subjectType", "PARTY").param("subjectId", acme.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("file"));
    }

    @Test
    void thePlansStorageQuotaIsEnforced() throws Exception {
        // plans.code is constrained to the real plan codes, so shrink the quota of the tenant's own plan and restore it.
        String original = OwnerJdbc.jdbc().queryForObject("select limits::text from plans where code = 'FREE'", String.class);
        OwnerJdbc.jdbc().update("update plans set limits = limits || '{\"maxStorageMb\": 1}'::jsonb where code = 'FREE'");
        try {
            upload(owner, acme, "a.bin", "application/octet-stream", new byte[600 * 1024]).andExpect(status().isCreated());
            upload(owner, acme, "b.bin", "application/octet-stream", new byte[600 * 1024]).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("Your plan allows 1 MB of documents. Delete some or upgrade to add more."));
        } finally {
            OwnerJdbc.jdbc().update("update plans set limits = ?::jsonb where code = 'FREE'", original);
        }
    }

    @Test
    void archivedRecordsTakeNoNewFilesButKeepTheirs() throws Exception {
        UUID id = Api.id(upload(owner, acme, "a.txt", "text/plain", new byte[] {65}));
        owner.post("/api/v1/parties/" + acme + "/archive", "");
        upload(owner, acme, "b.txt", "text/plain", new byte[] {66}).andExpect(status().isConflict());
        owner.get("/api/v1/documents?subjectType=PARTY&subjectId=" + acme).andExpect(jsonPath("$", Matchers.hasSize(1)));
        owner.get("/api/v1/documents/" + id + "/content").andExpect(status().isOk());
    }

    @Test
    void deletingRemovesTheContentToo() throws Exception {
        UUID id = Api.id(upload(owner, acme, "a.txt", "text/plain", new byte[] {65}));
        owner.delete("/api/v1/documents/" + id).andExpect(status().isNoContent());
        owner.get("/api/v1/documents/" + id + "/content").andExpect(status().isNotFound());
        owner.delete("/api/v1/documents/" + id).andExpect(status().isNotFound());
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select count(*) from document_contents", Long.class))
                .isZero();
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForList(
                "select action from audit_events where entity_id = ? order by occurred_at", String.class, id.toString()))
                .containsExactly("DocumentUploaded", "DocumentDeleted");
    }

    @Test
    void documentPermissionsStackOnTheSubjectsReadPermission() throws Exception {
        UUID id = Api.id(upload(owner, acme, "a.txt", "text/plain", new byte[] {65}));
        Api noParty = memberWith("collaboration.document.read", "collaboration.document.manage");
        noParty.get("/api/v1/documents?subjectType=PARTY&subjectId=" + acme).andExpect(status().isForbidden());
        noParty.get("/api/v1/documents/" + id + "/content").andExpect(status().isForbidden());
        upload(noParty, acme, "b.txt", "text/plain", new byte[] {66}).andExpect(status().isForbidden());

        Api reader = memberWith("collaboration.document.read", "directory.party.read");
        reader.get("/api/v1/documents/" + id + "/content").andExpect(status().isOk());
        upload(reader, acme, "b.txt", "text/plain", new byte[] {66}).andExpect(status().isForbidden());
        reader.delete("/api/v1/documents/" + id).andExpect(status().isForbidden());
        owner.get("/api/v1/documents/" + UUID.randomUUID() + "/content").andExpect(status().isNotFound());
    }
}
