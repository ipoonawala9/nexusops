package com.nexusops.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.IntegrationTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "nexusops.rate-limits.rules.platform-login.capacity=4",
        "nexusops.rate-limits.rules.platform-login-account.capacity=3"
})
class PlatformLoginRateLimitIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;

    private static String uniqueIp() {
        return "10.%d.%d.%d".formatted((int) (Math.random() * 250), (int) (Math.random() * 250), (int) (Math.random() * 250));
    }

    private ResultActions attempt(String email, String ip) throws Exception {
        return mvc.perform(post("/api/v1/platform/auth/login").with(r -> { r.setRemoteAddr(ip); return r; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"guess guess guess\",\"code\":\"123456\"}".formatted(email)));
    }

    @Test
    void theAccountBucketHoldsAcrossIpsAndCaseOrSpacingVariants() throws Exception {
        String email = "target-" + UUID.randomUUID().toString().substring(0, 8) + "@nexusops.test";
        attempt(email, uniqueIp()).andExpect(status().isUnauthorized());
        attempt(email.toUpperCase(), uniqueIp()).andExpect(status().isUnauthorized());
        attempt("  " + email + " ", uniqueIp()).andExpect(status().isUnauthorized());
        attempt(email, uniqueIp()).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        attempt("other-" + email, uniqueIp()).andExpect(status().isUnauthorized());
    }

    @Test
    void theIpBucketHoldsAcrossAccounts() throws Exception {
        String ip = uniqueIp();
        for (int i = 0; i < 4; i++) {
            attempt("spray-" + i + "-" + UUID.randomUUID().toString().substring(0, 6) + "@nexusops.test", ip)
                    .andExpect(status().isUnauthorized());
        }
        attempt("spray-last@nexusops.test", ip).andExpect(status().isTooManyRequests());
    }
}
