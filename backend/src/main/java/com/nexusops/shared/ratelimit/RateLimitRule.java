package com.nexusops.shared.ratelimit;

import java.time.Duration;

/** {@code capacity} requests per {@code window}, refilled continuously (token bucket). */
public record RateLimitRule(int capacity, Duration window) {

    public RateLimitRule {
        if (capacity < 1) {
            throw new IllegalArgumentException("Rate-limit capacity must be at least 1");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Rate-limit window must be positive");
        }
    }
}
