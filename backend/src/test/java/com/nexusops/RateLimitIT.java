package com.nexusops;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestTenants;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "nexusops.rate-limits.rules.login.capacity=3",
        "nexusops.rate-limits.rules.login-account.capacity=3",
        "nexusops.rate-limits.rules.login-workspace.capacity=5",
        "nexusops.rate-limits.rules.verify-email.capacity=2",
        "nexusops.rate-limits.rules.password-reset-request.capacity=2",
        "nexusops.rate-limits.rules.password-reset.capacity=2",
        "nexusops.rate-limits.rules.password-reset-account.capacity=2",
        "nexusops.rate-limits.rules.api.capacity=5"
})
class RateLimitIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired org.springframework.boot.data.redis.autoconfigure.DataRedisProperties redisProperties;

    @Test
    void redisTimeoutsAreBounded() {
        org.assertj.core.api.Assertions.assertThat(redisProperties.getTimeout()).isEqualTo(java.time.Duration.ofMillis(500));
        org.assertj.core.api.Assertions.assertThat(redisProperties.getConnectTimeout()).isEqualTo(java.time.Duration.ofMillis(500));
    }

    private static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder request, String ip) {
        return request.with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
    }

    private static String uniqueIp() {
        return "10.%d.%d.%d".formatted((int) (Math.random() * 250), (int) (Math.random() * 250), (int) (Math.random() * 250));
    }

    private ResultActions login(String ip, String workspace) throws Exception {
        return mvc.perform(from(post("/api/v1/auth/login"), ip).contentType(MediaType.APPLICATION_JSON).content("""
                {"workspace":"%s","email":"a@b.test","password":"whatever-123456"}""".formatted(workspace)));
    }

    @Test
    void loginIsLimitedPerIp() throws Exception {
        String ip = uniqueIp();
        for (int i = 0; i < 3; i++) {
            login(ip, "ws-" + UUID.randomUUID().toString().substring(0, 8)).andExpect(status().isUnauthorized());
        }
        login(ip, "ws-" + UUID.randomUUID().toString().substring(0, 8))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.title").value("Too Many Requests"))
                .andExpect(jsonPath("$.requestId").exists());
    }

    private ResultActions loginAs(String ip, String workspace, String email) throws Exception {
        return mvc.perform(from(post("/api/v1/auth/login"), ip).contentType(MediaType.APPLICATION_JSON).content("""
                {"workspace":"%s","email":"%s","password":"whatever-123456"}""".formatted(workspace, email)));
    }

    @Test
    void loginIsLimitedPerAccountAcrossIps() throws Exception {
        String workspace = "target-" + UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < 3; i++) {
            loginAs(uniqueIp(), workspace, "victim@b.test").andExpect(status().isUnauthorized());
        }
        loginAs(uniqueIp(), workspace, "victim@b.test").andExpect(status().isTooManyRequests());
        // another account in the same workspace is unaffected
        loginAs(uniqueIp(), workspace, "other@b.test").andExpect(status().isUnauthorized());
    }

    @Test
    void controlCharPaddedWorkspaceVariantsShareTheAccountBucket() throws Exception {
        var ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("rl-pad"));
        String slug = ws.slug();
        // JSON escapes: each variant canonicalizes (Slug.normalize trims chars <= U+0020) to the same real workspace.
        String[] variants = {slug, slug + "\\u0001", "\\u0002" + slug};
        for (String variant : variants) {
            loginAs(uniqueIp(), variant, ws.email()).andExpect(status().isUnauthorized());
        }
        loginAs(uniqueIp(), "\\u0003" + slug + "\\u0004", ws.email())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        // the bucket is keyed by the canonical account: the correct password from a fresh IP is limited too
        mvc.perform(from(post("/api/v1/auth/login"), uniqueIp()).contentType(MediaType.APPLICATION_JSON).content("""
                {"workspace":"%s\\u0005","email":" %s ","password":"%s"}""".formatted(slug, ws.email().toUpperCase(), ws.password())))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void workspaceBucketCatchesSprayingAcrossAccounts() throws Exception {
        String workspace = "spray-" + UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < 5; i++) {
            loginAs(uniqueIp(), workspace, "user" + i + "@b.test").andExpect(status().isUnauthorized());
        }
        loginAs(uniqueIp(), workspace, "user5@b.test").andExpect(status().isTooManyRequests());
    }

    @Test
    void otherPublicRoutesAreLimited() throws Exception {
        String ip = uniqueIp();
        for (int i = 0; i < 2; i++) {
            mvc.perform(from(post("/api/v1/auth/verify-email"), ip).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"token\":\"garbage\"}")).andExpect(status().isBadRequest());
        }
        mvc.perform(from(post("/api/v1/auth/verify-email"), ip).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"garbage\"}")).andExpect(status().isTooManyRequests());
    }

    @Test
    void passwordResetRoutesAreLimitedPerIp() throws Exception {
        String ip = uniqueIp();
        for (int i = 0; i < 2; i++) {
            mvc.perform(from(post("/api/v1/auth/password-reset/request"), ip).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"workspace\":\"nobody\",\"email\":\"a@b.test\"}")).andExpect(status().isNoContent());
            mvc.perform(from(post("/api/v1/auth/password-reset"), ip).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"token\":\"garbage\",\"password\":\"x\"}")).andExpect(status().isBadRequest());
        }
        mvc.perform(from(post("/api/v1/auth/password-reset/request"), ip).contentType(MediaType.APPLICATION_JSON)
                .content("{\"workspace\":\"nobody\",\"email\":\"a@b.test\"}")).andExpect(status().isTooManyRequests());
        mvc.perform(from(post("/api/v1/auth/password-reset"), ip).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"garbage\",\"password\":\"x\"}")).andExpect(status().isTooManyRequests());
    }

    @Test
    void passwordResetRequestsAreAlsoLimitedPerAccountAcrossIps() throws Exception {
        String account = "{\"workspace\":\"victim-ws\",\"email\":\"victim@b.test\"}";
        for (int i = 0; i < 2; i++) {
            mvc.perform(from(post("/api/v1/auth/password-reset/request"), uniqueIp())
                    .contentType(MediaType.APPLICATION_JSON).content(account)).andExpect(status().isNoContent());
        }
        // a third request for the same account is refused even from a fresh IP, and padded/cased variants share it
        mvc.perform(from(post("/api/v1/auth/password-reset/request"), uniqueIp()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"workspace\":\" Victim-WS \",\"email\":\"VICTIM@b.test \"}"))
                .andExpect(status().isTooManyRequests());
        // another account is unaffected
        mvc.perform(from(post("/api/v1/auth/password-reset/request"), uniqueIp()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"workspace\":\"victim-ws\",\"email\":\"other@b.test\"}")).andExpect(status().isNoContent());
    }

    @Test
    void authenticatedApiIsLimitedPerUser() throws Exception {
        var ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("rl-api"));
        var session = TestTenants.login(mvc, ws, uniqueIp());
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + session.accessToken()))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }
}
