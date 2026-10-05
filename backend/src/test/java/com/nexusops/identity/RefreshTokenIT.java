package com.nexusops.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.identity.application.OpaqueTokens;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Session;
import com.nexusops.support.TestTenants.Workspace;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class RefreshTokenIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    Workspace ws;
    Session session;

    @BeforeEach
    void login() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("rt"));
        session = TestTenants.login(mvc, ws);
    }

    private ResultActions refresh(String token) throws Exception {
        var request = post("/api/v1/auth/refresh");
        if (token != null) request.cookie(new Cookie("nexus_rt", token));
        return mvc.perform(request);
    }

    private String rotate(String token) throws Exception {
        return TestTenants.refreshCookieOf(refresh(token).andExpect(status().isOk()).andReturn());
    }

    @Test
    void refreshRotatesTheTokenAndIssuesANewAccessToken() throws Exception {
        String second = rotate(session.refreshToken());
        assertThat(second).isNotEqualTo(session.refreshToken());
        String third = rotate(second);
        assertThat(third).isNotEqualTo(second);
    }

    @Test
    void rotationKeepsTheFamilyAbsoluteExpiry() throws Exception {
        String second = rotate(session.refreshToken());
        var owner = OwnerJdbc.ownerAs(ws.tenantId());
        var first = owner.queryForObject("select expires_at from refresh_tokens where token_hash = ?",
                java.sql.Timestamp.class, OpaqueTokens.hash(session.refreshToken()));
        var next = owner.queryForObject("select expires_at from refresh_tokens where token_hash = ?",
                java.sql.Timestamp.class, OpaqueTokens.hash(second));
        assertThat(next).isEqualTo(first);
    }

    @Test
    void concurrentRefreshWithinGraceDoesNotRevokeFamily() throws Exception {
        String second = rotate(session.refreshToken());
        refresh(session.refreshToken()).andExpect(status().isUnauthorized()); // the "other tab"
        rotate(second); // the family is still alive
    }

    /**
     * Two refreshes of the same token race for real: the row lock in findForUpdateByTokenHash must
     * serialize them so exactly one rotates and the other sees a just-rotated token (grace, no revocation).
     * Several rounds, so a missing lock cannot pass by lucky scheduling.
     */
    @Test
    void trulyConcurrentRefreshesRotateExactlyOnce() throws Exception {
        var owner = OwnerJdbc.ownerAs(ws.tenantId());
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 5; round++) {
                String token = round == 0 ? session.refreshToken() : TestTenants.login(mvc, ws).refreshToken();
                CountDownLatch start = new CountDownLatch(1);
                Callable<MvcResult> attempt = () -> {
                    start.await();
                    return mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("nexus_rt", token))).andReturn();
                };
                Future<MvcResult> a = pool.submit(attempt);
                Future<MvcResult> b = pool.submit(attempt);
                start.countDown();
                List<MvcResult> results = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));

                assertThat(results).extracting(r -> r.getResponse().getStatus()).as("round %d", round)
                        .containsExactlyInAnyOrder(200, 401);
                assertThat(owner.queryForObject(
                        "select count(*) from refresh_tokens where revoke_reason = 'REUSE_DETECTED'", Long.class)).isZero();
                MvcResult winner = results.stream().filter(r -> r.getResponse().getStatus() == 200).findFirst().orElseThrow();
                rotate(TestTenants.refreshCookieOf(winner));
            }
        }
    }

    @Test
    void reuseAfterGraceRevokesTheWholeFamilyAndIsAudited() throws Exception {
        String second = rotate(session.refreshToken());
        var owner = OwnerJdbc.ownerAs(ws.tenantId());
        owner.update("update refresh_tokens set revoked_at = now() - interval '1 minute' where token_hash = ?",
                OpaqueTokens.hash(session.refreshToken()));

        refresh(session.refreshToken()).andExpect(status().isUnauthorized());
        refresh(second).andExpect(status().isUnauthorized());
        assertThat(owner.queryForList("select action from audit_events", String.class)).contains("RefreshTokenReuseDetected");
        assertThat(owner.queryForObject("select count(*) from refresh_tokens where revoke_reason = 'REUSE_DETECTED'",
                Long.class)).isPositive();
    }

    @Test
    void refreshTokenFromBeforeATokenVersionBumpIsRejectedAndItsFamilyRevoked() throws Exception {
        var owner = OwnerJdbc.ownerAs(ws.tenantId());
        // a logout-all whose revocation UPDATE missed this row (e.g. inserted by a concurrent refresh)
        owner.update("update users set token_version = token_version + 1");

        refresh(session.refreshToken()).andExpect(status().isUnauthorized());

        assertThat(owner.queryForList("select revoke_reason from refresh_tokens", String.class))
                .isNotEmpty().allMatch("LOGOUT_ALL"::equals);
    }

    @Test
    void expiredMissingAndTamperedTokensAreRejected() throws Exception {
        refresh(null).andExpect(status().isUnauthorized());
        refresh("garbage").andExpect(status().isUnauthorized());
        refresh(java.util.UUID.randomUUID() + session.refreshToken().substring(36)).andExpect(status().isUnauthorized());
        OwnerJdbc.ownerAs(ws.tenantId()).update("update refresh_tokens set expires_at = now() - interval '1 second'");
        refresh(session.refreshToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheFamilyAndClearsTheCookie() throws Exception {
        String second = rotate(session.refreshToken());
        mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("nexus_rt", second)))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        refresh(second).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isNoContent()); // idempotent without cookie
    }

    @Test
    void crossSiteOriginIsRejectedButSameOriginAllowed() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").header("Origin", "https://evil.example")
                        .cookie(new Cookie("nexus_rt", session.refreshToken())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/refresh").header("Origin", "http://localhost:5173")
                        .cookie(new Cookie("nexus_rt", session.refreshToken())))
                .andExpect(status().isOk());
    }
}
