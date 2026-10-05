package com.nexusops.shared.ratelimit;

import java.time.Duration;

/** {@code capacity} requests per {@code window}, refilled continuously (token bucket). */
public record RateLimitRule(int capacity, Duration window) {}
