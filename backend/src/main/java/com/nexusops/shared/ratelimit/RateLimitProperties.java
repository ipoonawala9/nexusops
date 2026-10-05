package com.nexusops.shared.ratelimit;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("nexusops.rate-limits")
public record RateLimitProperties(Map<String, RateLimitRule> rules) {}
