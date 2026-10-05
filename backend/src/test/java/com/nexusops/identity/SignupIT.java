package com.nexusops.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.identity.domain.User;
import com.nexusops.identity.domain.UserRepository;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestTenants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
class SignupIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired UserRepository users;
    @Autowired TransactionTemplate tx;

    private ResultActions signup(String json) throws Exception {
        return mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String body(String slug, String email, String password) {
        return """
                {"workspaceName":"Acme","slug":"%s","firstName":"Ada","lastName":"Owner","email":"%s","password":"%s"}
                """.formatted(slug, email, password);
    }

    @Test
    void signupCreatesPendingWorkspaceOwnerRolesAuditAndMail() throws Exception {
        String slug = TestTenants.uniqueSlug("acme");
        signup(body(slug, "owner@" + slug + ".test", TestTenants.PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value(slug))
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"));

        var tenantId = OwnerJdbc.jdbc().queryForObject("select id from tenants where slug = ?", java.util.UUID.class, slug);
        var owner = OwnerJdbc.ownerAs(tenantId);
        assertThat(owner.queryForObject("""
                select r.name from users u join user_roles ur on ur.user_id = u.id join roles r on r.id = ur.role_id
                where u.email = ?""", String.class, "owner@" + slug + ".test")).isEqualTo("TENANT_OWNER");
        assertThat(owner.queryForList("select action from audit_events order by occurred_at", String.class))
                .contains("TenantCreated", "UserRegistered");
        assertThat(owner.queryForObject("select password_hash from users", String.class)).startsWith("$argon2id$");
        assertThat(mail.sentTo("owner@" + slug + ".test")).singleElement()
                .satisfies(m -> assertThat(m.textBody()).contains("/verify-email?token="));
    }

    @Test
    void emailIsNormalized() throws Exception {
        String slug = TestTenants.uniqueSlug("norm");
        signup(body(slug, "  Owner@" + slug.toUpperCase() + ".TEST ", TestTenants.PASSWORD)).andExpect(status().isCreated());
        var tenantId = OwnerJdbc.jdbc().queryForObject("select id from tenants where slug = ?", java.util.UUID.class, slug);
        assertThat(OwnerJdbc.ownerAs(tenantId).queryForObject("select email from users", String.class))
                .isEqualTo("owner@" + slug + ".test");
        assertThat(mail.sentTo("owner@" + slug + ".test")).hasSize(1);
    }

    @Test
    void duplicateEmailCaseVariantRejected() throws Exception {
        var workspace = TestTenants.signup(mvc, TestTenants.uniqueSlug("dupe"));
        // Same normalized email in the same tenant violates UNIQUE (tenant_id, email);
        // a non-normalized email violates the CHECK constraint. (Invitations in Plan 3 surface these as 409.)
        assertThatThrownBy(() -> TenantContext.runAs(workspace.tenantId(), () -> tx.executeWithoutResult(s ->
                users.saveAndFlush(User.registerOwner(Ids.newId(), workspace.email(), "h", "X", "Y", null)))))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> TenantContext.runAs(workspace.tenantId(), () -> tx.executeWithoutResult(s ->
                users.saveAndFlush(User.registerOwner(Ids.newId(), workspace.email().toUpperCase(), "h", "X", "Y", null)))))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void duplicateSlugIsAConflict() throws Exception {
        String slug = TestTenants.uniqueSlug("taken");
        TestTenants.signup(mvc, slug);
        signup(body(slug.toUpperCase(), "other@x.test", TestTenants.PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("slug"));
    }

    @Test
    void invalidInputIsReportedPerField() throws Exception {
        String slug = TestTenants.uniqueSlug("bad");
        signup(body(slug, "owner@x.test", "Password1234")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
        signup(body(slug, "not-an-email", TestTenants.PASSWORD)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("email"));
        signup(body("api", "owner@x.test", TestTenants.PASSWORD)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("slug"));
        signup("{\"slug\":\"" + slug + "\"}").andExpect(status().isBadRequest());
        assertThat(OwnerJdbc.jdbc().queryForObject("select count(*) from tenants where slug = ?", Long.class, slug)).isZero();
    }

    @Test
    void verificationActivatesWorkspaceAndIsSingleUse() throws Exception {
        var workspace = TestTenants.signup(mvc, TestTenants.uniqueSlug("verify"));
        String token = mail.lastTokenFor(workspace.email());
        verify(token).andExpect(status().isNoContent());
        assertThat(OwnerJdbc.jdbc().queryForObject("select status from tenants where id = ?", String.class, workspace.tenantId()))
                .isEqualTo("ACTIVE");
        assertThat(OwnerJdbc.ownerAs(workspace.tenantId()).queryForObject(
                "select email_verified_at is not null from users", Boolean.class)).isTrue();
        verify(token).andExpect(status().isBadRequest());
    }

    @Test
    void verificationRejectsExpiredTamperedAndGarbageTokens() throws Exception {
        var workspace = TestTenants.signup(mvc, TestTenants.uniqueSlug("expire"));
        String token = mail.lastTokenFor(workspace.email());
        String tampered = java.util.UUID.randomUUID() + token.substring(36);
        verify(tampered).andExpect(status().isBadRequest());
        verify("garbage").andExpect(status().isBadRequest());
        OwnerJdbc.ownerAs(workspace.tenantId()).update("update email_verifications set expires_at = now() - interval '1 minute'");
        verify(token).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("This verification link is invalid or has expired."));
    }

    @Test
    void resendIsAlwaysAcceptedAndReplacesThePreviousLink() throws Exception {
        var workspace = TestTenants.signup(mvc, TestTenants.uniqueSlug("resend"));
        String first = mail.lastTokenFor(workspace.email());

        resend("no-such-workspace", workspace.email()).andExpect(status().isAccepted());
        resend(workspace.slug(), "nobody@x.test").andExpect(status().isAccepted());
        assertThat(mail.sentTo(workspace.email())).hasSize(1);

        resend(workspace.slug(), workspace.email()).andExpect(status().isAccepted());
        String second = mail.lastTokenFor(workspace.email());
        assertThat(second).isNotEqualTo(first);
        verify(first).andExpect(status().isBadRequest());
        verify(second).andExpect(status().isNoContent());

        resend(workspace.slug(), workspace.email()).andExpect(status().isAccepted());
        assertThat(mail.sentTo(workspace.email())).hasSize(2); // already verified: no further mail
    }

    private ResultActions verify(String token) throws Exception {
        return mvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"));
    }

    private ResultActions resend(String workspace, String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/resend-verification").contentType(MediaType.APPLICATION_JSON)
                .content("{\"workspace\":\"" + workspace + "\",\"email\":\"" + email + "\"}"));
    }
}
