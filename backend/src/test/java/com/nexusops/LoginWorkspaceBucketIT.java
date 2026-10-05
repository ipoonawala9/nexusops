package com.nexusops;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestTenants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The login-workspace bucket counts only FAILED logins (ADR-0006); per-IP and per-account stay high here. */
@AutoConfigureMockMvc
@TestPropertySource(properties = "nexusops.rate-limits.rules.login-workspace.capacity=3")
class LoginWorkspaceBucketIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    private static String uniqueIp() {
        return "10.%d.%d.%d".formatted((int) (Math.random() * 250), (int) (Math.random() * 250), (int) (Math.random() * 250));
    }

    private ResultActions login(String workspace, String email, String password) throws Exception {
        String ip = uniqueIp();
        return mvc.perform(post("/api/v1/auth/login").with(r -> { r.setRemoteAddr(ip); return r; })
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"workspace":"%s","email":"%s","password":"%s"}""".formatted(workspace, email, password)));
    }

    @Test
    void successfulLoginsBeyondCapacityNeverHitTheWorkspaceBucket() throws Exception {
        var ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("rl-ok"));
        for (int i = 0; i < 8; i++) {
            login(ws.slug(), ws.email(), ws.password()).andExpect(status().isOk());
        }
    }

    @Test
    void failuresFromManyIpsAndAccountsExhaustTheWorkspaceBucket() throws Exception {
        var ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("rl-fail"));
        login(ws.slug(), ws.email(), "wrong password 1").andExpect(status().isUnauthorized());
        login(ws.slug(), "nobody1@" + ws.slug() + ".test", "whatever-123456").andExpect(status().isUnauthorized());
        login(ws.slug(), "nobody2@" + ws.slug() + ".test", "whatever-123456").andExpect(status().isUnauthorized());
        login(ws.slug(), "nobody3@" + ws.slug() + ".test", "whatever-123456")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        // once exhausted by failures, the workspace's logins are blocked for the window (documented residual risk)
        login(ws.slug(), ws.email(), ws.password()).andExpect(status().isTooManyRequests());
    }
}
