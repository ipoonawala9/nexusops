package com.nexusops.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.support.IntegrationTestSupport;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RedisRateLimiterIT extends IntegrationTestSupport {

    @Autowired RedisRateLimiter limiter;

    private static String key() {
        return "rl:test:" + UUID.randomUUID();
    }

    @Test
    void allowsUpToCapacityThenDeniesWithRetryAfter() {
        var rule = new RateLimitRule(3, Duration.ofMinutes(1));
        String key = key();
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryConsume(key, rule).allowed()).isTrue();
        }
        var denied = limiter.tryConsume(key, rule);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 60L);
    }

    @Test
    void bucketsAreIndependentPerKey() {
        var rule = new RateLimitRule(1, Duration.ofMinutes(1));
        String a = key();
        assertThat(limiter.tryConsume(a, rule).allowed()).isTrue();
        assertThat(limiter.tryConsume(a, rule).allowed()).isFalse();
        assertThat(limiter.tryConsume(key(), rule).allowed()).isTrue();
    }

    @Test
    void tokensRefillOverTheWindow() throws InterruptedException {
        var rule = new RateLimitRule(2, Duration.ofMillis(400));
        String key = key();
        limiter.tryConsume(key, rule);
        limiter.tryConsume(key, rule);
        assertThat(limiter.tryConsume(key, rule).allowed()).isFalse();
        Thread.sleep(450);
        assertThat(limiter.tryConsume(key, rule).allowed()).isTrue();
    }

    @Test
    void peekNeverConsumes() {
        var rule = new RateLimitRule(2, Duration.ofMinutes(1));
        String key = key();
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.peek(key, rule).allowed()).isTrue();
        }
        limiter.tryConsume(key, rule);
        limiter.tryConsume(key, rule);
        var denied = limiter.peek(key, rule);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 60L);
        assertThat(limiter.tryConsume(key, rule).allowed()).isFalse();
    }
}
