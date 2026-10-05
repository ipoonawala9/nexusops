package com.nexusops.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexusops.shared.web.ApiProblem;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RateLimitsTest {

    final RedisRateLimiter limiter = mock(RedisRateLimiter.class);
    final RateLimitRule rule = new RateLimitRule(10, Duration.ofMinutes(1));
    final RateLimits limits = new RateLimits(limiter,
            new RateLimitProperties(Map.of("login", rule, "api", rule, "signup", rule)));

    @Test
    void exceededPublicLimitIs429WithRetryAfter() {
        when(limiter.tryConsume(anyString(), any())).thenReturn(new RedisRateLimiter.Decision(false, 17));
        assertThatThrownBy(() -> limits.checkPublic("signup", "10.0.0.1"))
                .isInstanceOfSatisfying(RateLimitExceeded.class, e -> {
                    assertThat(e.status().value()).isEqualTo(429);
                    assertThat(e.retryAfterSeconds()).isEqualTo(17);
                    assertThat(e.getMessage()).isEqualTo("Too many requests. Try again in 17 seconds.");
                });
    }

    @Test
    void publicRoutesFailClosedWhenRedisIsDown() {
        when(limiter.tryConsume(anyString(), any())).thenThrow(new RateLimiterUnavailableException(new RuntimeException()));
        assertThatThrownBy(() -> limits.checkLoginWorkspace("acme"))
                .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.status().value()).isEqualTo(503));
    }

    @Test
    void apiFailsOpenWhenRedisIsDown() {
        when(limiter.tryConsume(anyString(), any())).thenThrow(new RateLimiterUnavailableException(new RuntimeException()));
        assertThat(limits.apiRetryAfter(UUID.randomUUID(), UUID.randomUUID())).isEmpty();
    }

    @Test
    void unknownRuleIsAProgrammingError() {
        assertThatThrownBy(() -> limits.checkPublic("nope", "10.0.0.1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void workspaceKeysAreNormalizedAndHashed() {
        assertThat(RateLimitKeys.workspace("login", " ACME ")).isEqualTo(RateLimitKeys.workspace("login", "acme"))
                .startsWith("rl:ws:").endsWith(":login").doesNotContain("acme");
        assertThat(RateLimitKeys.ip("login", "bad ip\n")).isEqualTo("rl:ip:unknown:login");
    }
}
