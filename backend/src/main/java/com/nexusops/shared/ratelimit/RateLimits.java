package com.nexusops.shared.ratelimit;

import com.nexusops.shared.web.ApiProblem;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/** Public auth routes fail CLOSED when Redis is down; the authenticated API fails OPEN (spec §9). */
@Service
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimits {

    private static final Logger log = LoggerFactory.getLogger(RateLimits.class);
    static final String UNAVAILABLE = "Service temporarily unavailable. Please try again shortly.";

    private final RedisRateLimiter limiter;
    private final RateLimitProperties properties;

    public RateLimits(RedisRateLimiter limiter, RateLimitProperties properties) {
        this.limiter = limiter;
        this.properties = properties;
    }

    public void checkPublic(String rule, String clientIp) {
        enforce(RateLimitKeys.ip(rule, clientIp), rule(rule));
    }

    /**
     * Login: per-IP, then per-account (workspace+email), then a high per-workspace spraying backstop. All fail closed.
     * The workspace and email must already be in the canonical form the login lookup uses (the caller owns that
     * normalization); {@code null} means the value cannot canonicalize, so the login cannot succeed and only the
     * per-IP bucket is charged.
     */
    public void checkLogin(String clientIp, String canonicalWorkspace, String canonicalEmail) {
        enforce(RateLimitKeys.ip("login", clientIp), rule("login"));
        if (canonicalWorkspace == null || canonicalEmail == null) {
            return;
        }
        enforce(RateLimitKeys.account(canonicalWorkspace, canonicalEmail), rule("login-account"));
        enforce(RateLimitKeys.workspace("login-workspace", canonicalWorkspace), rule("login-workspace"));
    }

    public OptionalLong apiRetryAfter(UUID tenantId, UUID userId) {
        try {
            var decision = limiter.tryConsume(RateLimitKeys.api(tenantId, userId), rule("api"));
            return decision.allowed() ? OptionalLong.empty() : OptionalLong.of(decision.retryAfterSeconds());
        } catch (RateLimiterUnavailableException e) {
            log.warn("Rate limiter unavailable; allowing authenticated API request", e);
            return OptionalLong.empty();
        }
    }

    private void enforce(String key, RateLimitRule rule) {
        RedisRateLimiter.Decision decision;
        try {
            decision = limiter.tryConsume(key, rule);
        } catch (RateLimiterUnavailableException e) {
            log.warn("Rate limiter unavailable; rejecting public auth request", e);
            throw ApiProblem.serviceUnavailable(UNAVAILABLE);
        }
        if (!decision.allowed()) {
            throw new RateLimitExceeded(decision.retryAfterSeconds());
        }
    }

    private RateLimitRule rule(String name) {
        RateLimitRule rule = properties.rules() == null ? null : properties.rules().get(name);
        if (rule == null) {
            throw new IllegalArgumentException("No rate-limit rule configured: " + name);
        }
        return rule;
    }
}
