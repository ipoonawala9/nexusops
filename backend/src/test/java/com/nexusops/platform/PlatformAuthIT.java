package com.nexusops.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.platform.application.PlatformUserAdmin;
import com.nexusops.platform.domain.PlatformRole;
import com.nexusops.platform.domain.PlatformUserStatus;
import com.nexusops.platform.security.PlatformSessionTokens;
import com.nexusops.platform.totp.Totp;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.TestPlatformUsers;
import com.nexusops.support.TestPlatformUsers.Operator;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class PlatformAuthIT extends IntegrationTestSupport {

    static final String INVALID = "Invalid email, password or code.";
    static final Pattern ACCESS = Pattern.compile("\"accessToken\":\"([^\"]+)\"");
    static final Pattern COOKIE = Pattern.compile("nexus_prt=([^;]*)");

    @Autowired MockMvc mvc;
    @Autowired PlatformUserAdmin admin;

    Operator op;

    @BeforeEach
    void operator() {
        op = TestPlatformUsers.create(admin, PlatformRole.PLATFORM_ADMIN);
    }

    private ResultActions login(String email, String password, String code) throws Exception {
        return mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"%s","code":"%s"}""".formatted(email, password, code)));
    }

    private ResultActions refresh(String cookie) throws Exception {
        return mvc.perform(post("/api/v1/platform/auth/refresh").cookie(new Cookie("nexus_prt", cookie)));
    }

    private static String cookieOf(MvcResult result) {
        for (String header : result.getResponse().getHeaders("Set-Cookie")) {
            var m = COOKIE.matcher(header);
            if (m.find()) return m.group(1);
        }
        throw new AssertionError("no nexus_prt cookie");
    }

    private static String accessOf(MvcResult result) throws Exception {
        var m = ACCESS.matcher(result.getResponse().getContentAsString());
        if (!m.find()) throw new AssertionError("no accessToken");
        return m.group(1);
    }

    private Timestamp expiresAt(String cookie) {
        return TestPlatformUsers.platformJdbc().queryForObject(
                "select expires_at from platform_refresh_tokens where token_hash = ?", Timestamp.class,
                PlatformSessionTokens.hash(cookie));
    }

    private long nextStep() {
        return Totp.step(Instant.now()) + 1;
    }

    @Test
    void signsInWithPasswordAndCodeAndSetsAScopedCookie() throws Exception {
        MvcResult result = login(op.email().toUpperCase(), op.password(), op.currentCode())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andReturn();
        String setCookie = String.join("\n", result.getResponse().getHeaders("Set-Cookie"));
        assertThat(setCookie).contains("nexus_prt=").contains("Path=/api/v1/platform/auth").contains("HttpOnly")
                .contains("Secure").contains("SameSite=Strict").contains("Max-Age=");
        mvc.perform(get("/api/v1/platform/me").header("Authorization", "Bearer " + accessOf(result)))
                .andExpect(status().isOk());
        assertThat(OwnerJdbc.superuser().queryForObject("""
                select count(*) from audit_events where action = 'PlatformLoginSucceeded' and tenant_id is null
                  and actor_type = 'PLATFORM' and actor_id = ?""", Long.class, op.id())).isOne();
    }

    @Test
    void everyFailureLooksTheSame() throws Exception {
        long far = Totp.step(Instant.now()) + 5;
        login(op.email(), "wrong platform password!", op.currentCode())
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value(INVALID));
        login(op.email(), op.password(), op.codeAt(far))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value(INVALID));
        login("nobody@nexusops.test", op.password(), op.currentCode())
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value(INVALID));
        Operator disabled = TestPlatformUsers.create(admin, PlatformRole.PLATFORM_SUPPORT);
        admin.setStatus(disabled.email(), PlatformUserStatus.DISABLED);
        login(disabled.email(), disabled.password(), disabled.currentCode())
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value(INVALID));

        List<String> reasons = OwnerJdbc.superuser().queryForList("""
                select metadata->>'reason' from audit_events where action = 'PlatformLoginFailed'
                  and (entity_id = ? or entity_id = ? or entity_id is null)""", String.class,
                op.id().toString(), disabled.id().toString());
        assertThat(reasons).contains("BAD_PASSWORD", "BAD_CODE", "UNKNOWN_USER", "DISABLED");
    }

    @Test
    void aTotpCodeWorksOnlyOnce() throws Exception {
        String code = op.currentCode();
        login(op.email(), op.password(), code).andExpect(status().isOk());
        login(op.email(), op.password(), code)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value(INVALID));
    }

    @Test
    void refreshRotatesAndDetectsReuseAfterTheGraceWindow() throws Exception {
        String first = cookieOf(login(op.email(), op.password(), op.currentCode()).andExpect(status().isOk()).andReturn());
        String second = cookieOf(refresh(first).andExpect(status().isOk()).andReturn());
        assertThat(second).isNotEqualTo(first);
        refresh(first).andExpect(status().isUnauthorized()); // inside the 10 s grace window: family kept
        String third = cookieOf(refresh(second).andExpect(status().isOk()).andReturn());

        TestPlatformUsers.platformJdbc().update(
                "update platform_refresh_tokens set revoked_at = revoked_at - interval '11 seconds' where token_hash = ?",
                PlatformSessionTokens.hash(first));
        refresh(first).andExpect(status().isUnauthorized());
        refresh(third).andExpect(status().isUnauthorized()); // the whole family was revoked
        assertThat(OwnerJdbc.superuser().queryForObject(
                "select count(*) from audit_events where action = 'PlatformRefreshTokenReuseDetected' and actor_id = ?",
                Long.class, op.id())).isOne();
    }

    @Test
    void rotationNeverExtendsTheEightHourCap() throws Exception {
        String first = cookieOf(login(op.email(), op.password(), op.currentCode()).andExpect(status().isOk()).andReturn());
        Instant cap = expiresAt(first).toInstant();
        assertThat(Duration.between(Instant.now(), cap)).isBetween(Duration.ofHours(8).minusMinutes(1), Duration.ofHours(8));
        String second = cookieOf(refresh(first).andExpect(status().isOk()).andReturn());
        assertThat(expiresAt(second).toInstant()).isEqualTo(cap);

        TestPlatformUsers.platformJdbc().update(
                "update platform_refresh_tokens set expires_at = now() - interval '1 second' where platform_user_id = ?",
                op.id());
        refresh(second).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Your session has expired. Please sign in again."));
    }

    @Test
    void logoutEndsTheFamilyAndClearsTheCookie() throws Exception {
        String cookie = cookieOf(login(op.email(), op.password(), op.currentCode()).andExpect(status().isOk()).andReturn());
        MvcResult out = mvc.perform(post("/api/v1/platform/auth/logout").cookie(new Cookie("nexus_prt", cookie)))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(String.join("\n", out.getResponse().getHeaders("Set-Cookie"))).contains("nexus_prt=").contains("Max-Age=0");
        refresh(cookie).andExpect(status().isUnauthorized());
        assertThat(OwnerJdbc.superuser().queryForObject(
                "select count(*) from audit_events where action = 'PlatformLogout' and actor_id = ?", Long.class, op.id()))
                .isOne();
    }

    @Test
    void credentialChangesEndExistingSessions() throws Exception {
        MvcResult result = login(op.email(), op.password(), op.currentCode()).andExpect(status().isOk()).andReturn();
        admin.resetPassword(op.email(), "a different platform passphrase");
        refresh(cookieOf(result)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/platform/me").header("Authorization", "Bearer " + accessOf(result)))
                .andExpect(status().isUnauthorized());
        login(op.email(), "a different platform passphrase", op.codeAt(nextStep())).andExpect(status().isOk());
    }

    @Test
    void refreshAndLogoutRejectAForeignOrigin() throws Exception {
        String cookie = cookieOf(login(op.email(), op.password(), op.currentCode()).andExpect(status().isOk()).andReturn());
        mvc.perform(post("/api/v1/platform/auth/refresh").cookie(new Cookie("nexus_prt", cookie))
                .header("Origin", "https://evil.example")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/platform/auth/logout").cookie(new Cookie("nexus_prt", cookie))
                .header("Origin", "https://evil.example")).andExpect(status().isForbidden());
        refresh("garbage").andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/platform/auth/refresh")).andExpect(status().isUnauthorized());
    }
}
