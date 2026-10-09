package com.nexusops.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class PasswordResetIT extends IntegrationTestSupport {

    static final String NEW_PASSWORD = "a brand new passphrase";
    static final String INVALID_LINK = "This reset link is invalid or has expired.";

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    Workspace ws;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("reset"));
    }

    private ResultActions requestReset(String workspace, String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                .content("{\"workspace\":\"" + workspace + "\",\"email\":\"" + email + "\"}"));
    }

    private ResultActions reset(String token, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/password-reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"password\":\"" + password + "\"}"));
    }

    private ResultActions login(String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"workspace":"%s","email":"%s","password":"%s"}""".formatted(ws.slug(), ws.email(), password)));
    }

    private int resetMails() {
        return (int) mail.sentTo(ws.email()).stream().filter(m -> m.subject().startsWith("Reset your")).count();
    }

    private String requestAndReadToken() throws Exception {
        requestReset(ws.slug(), ws.email()).andExpect(status().isNoContent());
        return mail.lastTokenFor(ws.email());
    }

    @Test
    void requestSendsOneMailWithAResetLinkAndTheTokenSetsANewPassword() throws Exception {
        requestReset(ws.slug(), ws.email()).andExpect(status().isNoContent());

        var mails = mail.sentTo(ws.email());
        assertThat(mails).hasSize(2); // verification + reset
        var resetMail = mails.getLast();
        assertThat(resetMail.subject()).isEqualTo("Reset your NexusOps password");
        assertThat(resetMail.textBody()).contains("/reset-password?token=").contains("expires in 1 hour")
                .contains("If you didn't ask for this, ignore this email — your password stays the same.");

        reset(mail.lastTokenFor(ws.email()), NEW_PASSWORD).andExpect(status().isNoContent());

        login(ws.password()).andExpect(status().isUnauthorized());
        login(NEW_PASSWORD).andExpect(status().isOk());
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select password_hash from users", String.class))
                .startsWith("$argon2id$");
    }

    @Test
    void requestIsCaseAndWhitespaceInsensitive() throws Exception {
        requestReset(" " + ws.slug().toUpperCase(), ws.email().toUpperCase()).andExpect(status().isNoContent());
        assertThat(resetMails()).isEqualTo(1);
    }

    @Test
    void resetSignsTheUserOutEverywhere() throws Exception {
        var session = TestTenants.login(mvc, ws);
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk());

        reset(requestAndReadToken(), NEW_PASSWORD).andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("nexus_rt", session.refreshToken())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isUnauthorized());
        var owner = OwnerJdbc.ownerAs(ws.tenantId());
        assertThat(owner.queryForList("select revoke_reason from refresh_tokens", String.class))
                .isNotEmpty().allMatch("PASSWORD_RESET"::equals);
        assertThat(owner.queryForObject("select token_version from users", Integer.class)).isEqualTo(1);
    }

    @Test
    void tokenIsSingleUse() throws Exception {
        String token = requestAndReadToken();
        reset(token, NEW_PASSWORD).andExpect(status().isNoContent());
        reset(token, "yet another passphrase").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(INVALID_LINK));
        login(NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        String token = requestAndReadToken();
        OwnerJdbc.ownerAs(ws.tenantId()).update("update password_resets set expires_at = now() - interval '1 minute'");
        reset(token, NEW_PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(INVALID_LINK));
        login(ws.password()).andExpect(status().isOk());
    }

    @Test
    void aSecondRequestInvalidatesTheFirstToken() throws Exception {
        String first = requestAndReadToken();
        String second = requestAndReadToken();
        assertThat(second).isNotEqualTo(first);
        reset(first, NEW_PASSWORD).andExpect(status().isBadRequest());
        reset(second, NEW_PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    void aSuccessfulResetInvalidatesTheUsersOtherOpenTokens() throws Exception {
        String first = requestAndReadToken();
        String second = requestAndReadToken();
        // re-open the first token to simulate one the second request's invalidation missed
        OwnerJdbc.ownerAs(ws.tenantId()).update(
                "update password_resets set used_at = null where token_hash = ?",
                com.nexusops.identity.application.OpaqueTokens.hash(first));
        reset(second, NEW_PASSWORD).andExpect(status().isNoContent());
        reset(first, "yet another passphrase").andExpect(status().isBadRequest());
        login(NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void unknownWorkspaceUnknownEmailAndMalformedEmailAreSilent() throws Exception {
        requestReset("no-such-workspace", ws.email()).andExpect(status().isNoContent());
        requestReset(ws.slug(), "nobody@x.test").andExpect(status().isNoContent());
        requestReset(ws.slug(), "not an email").andExpect(status().isNoContent());
        assertThat(resetMails()).isZero();
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select count(*) from password_resets", Long.class))
                .isZero();
    }

    @Test
    void disabledAndInvitedUsersGetNoLink() throws Exception {
        var owner = OwnerJdbc.ownerAs(ws.tenantId());
        owner.update("update users set status = 'DISABLED'");
        requestReset(ws.slug(), ws.email()).andExpect(status().isNoContent());
        owner.update("update users set status = 'INVITED'");
        requestReset(ws.slug(), ws.email()).andExpect(status().isNoContent());
        assertThat(resetMails()).isZero();
    }

    @Test
    void unverifiedUsersGetNoLink() throws Exception {
        Workspace pending = TestTenants.signup(mvc, TestTenants.uniqueSlug("pending"));
        requestReset(pending.slug(), pending.email()).andExpect(status().isNoContent());
        assertThat(mail.sentTo(pending.email())).hasSize(1); // only the verification mail
        assertThat(OwnerJdbc.ownerAs(pending.tenantId()).queryForObject("select count(*) from password_resets", Long.class))
                .isZero();
    }

    @Test
    void aTokenIssuedBeforeTheUserWasDisabledNoLongerWorks() throws Exception {
        String token = requestAndReadToken();
        OwnerJdbc.ownerAs(ws.tenantId()).update("update users set status = 'DISABLED'");
        reset(token, NEW_PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(INVALID_LINK));
    }

    @Test
    void weakPasswordIsRejectedPerFieldAndKeepsTheTokenUsable() throws Exception {
        String token = requestAndReadToken();
        reset(token, "short").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
        reset(token, "Password1234").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
        reset(token, ws.email()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
        login(ws.password()).andExpect(status().isOk());
        reset(token, NEW_PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    void tamperedForeignAndGarbageTokensAreRejected() throws Exception {
        Workspace other = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("other"));
        String token = requestAndReadToken();
        reset(java.util.UUID.randomUUID() + token.substring(36), NEW_PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(INVALID_LINK));
        // tenant B's id in front of tenant A's secret finds nothing under B's RLS context
        reset(other.tenantId() + token.substring(36), NEW_PASSWORD).andExpect(status().isBadRequest());
        reset("garbage", NEW_PASSWORD).andExpect(status().isBadRequest());
        login(ws.password()).andExpect(status().isOk());
    }

    @Test
    void validationRejectsBlankInput() throws Exception {
        mvc.perform(post("/api/v1/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                .content("{\"workspace\":\"\",\"email\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/password-reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"\",\"password\":\"x\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/password-reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"abc\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void requestAndResetAreAudited() throws Exception {
        String token = requestAndReadToken();
        var owner = OwnerJdbc.ownerAs(ws.tenantId());
        assertThat(owner.queryForList("select action from audit_events", String.class)).contains("PasswordResetRequested")
                .doesNotContain("PasswordReset");
        reset(token, NEW_PASSWORD).andExpect(status().isNoContent());
        assertThat(owner.queryForList("select action from audit_events", String.class))
                .contains("PasswordResetRequested", "PasswordReset");

        // a silent no-op is not audited
        requestReset(ws.slug(), "nobody@x.test").andExpect(status().isNoContent());
        assertThat(owner.queryForObject(
                "select count(*) from audit_events where action = 'PasswordResetRequested'", Long.class)).isEqualTo(1);
    }
}
