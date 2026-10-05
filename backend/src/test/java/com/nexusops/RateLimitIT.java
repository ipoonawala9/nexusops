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
        "nexusops.rate-limits.rules.verify-email.capacity=2",
        "nexusops.rate-limits.rules.api.capacity=5"
})
class RateLimitIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

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

    @Test
    void loginIsLimitedPerWorkspaceAcrossIps() throws Exception {
        String workspace = "target-" + UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < 3; i++) {
            login(uniqueIp(), workspace).andExpect(status().isUnauthorized());
        }
        login(uniqueIp(), workspace).andExpect(status().isTooManyRequests());
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
