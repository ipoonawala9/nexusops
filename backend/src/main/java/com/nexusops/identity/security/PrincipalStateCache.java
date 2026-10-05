package com.nexusops.identity.security;

import com.nexusops.authorization.AuthorizationService;
import com.nexusops.identity.domain.User;
import com.nexusops.identity.domain.UserRepository;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.cache.TenantKeys;
import com.nexusops.tenancy.TenantDirectory;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Per-request principal state with a short Redis TTL. Explicit eviction keeps it fresh; if Redis is
 * unavailable we fall back to the database (still correct, just slower).
 */
@Component
public class PrincipalStateCache {

    private static final Logger log = LoggerFactory.getLogger(PrincipalStateCache.class);
    static final Duration TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final UserRepository users;
    private final TenantDirectory tenants;
    private final AuthorizationService authorization;
    private final TransactionTemplate tx;

    PrincipalStateCache(StringRedisTemplate redis, JsonMapper json, UserRepository users, TenantDirectory tenants,
            AuthorizationService authorization, TransactionTemplate tx) {
        this.redis = redis;
        this.json = json;
        this.users = users;
        this.tenants = tenants;
        this.authorization = authorization;
        this.tx = tx;
    }

    /** Must be called with TenantContext bound to {@code tenantId}. */
    public PrincipalState get(UUID tenantId, UUID userId) {
        if (!tenantId.equals(TenantContext.requireTenantId())) {
            throw new IllegalStateException("Principal lookup outside its tenant scope");
        }
        String key = key(tenantId, userId);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return json.readValue(cached, PrincipalState.class);
            }
        } catch (RuntimeException e) {
            log.warn("Principal cache read failed; using database", e);
        }
        PrincipalState state = load(userId);
        try {
            redis.opsForValue().set(key, json.writeValueAsString(state), TTL);
        } catch (RuntimeException e) {
            log.warn("Principal cache write failed", e);
        }
        return state;
    }

    public void evict(UUID tenantId, UUID userId) {
        redis.delete(key(tenantId, userId));
    }

    public void evictTenant(UUID tenantId) {
        try (Cursor<String> keys = redis.scan(ScanOptions.scanOptions().match(TenantKeys.tenantPattern(tenantId)).count(500).build())) {
            keys.forEachRemaining(redis::delete);
        }
    }

    private PrincipalState load(UUID userId) {
        return tx.execute(status -> {
            User user = users.findById(userId).orElse(null);
            if (user == null) {
                return PrincipalState.missing();
            }
            var tenant = tenants.current();
            List<String> modules = tenants.enabledModules();
            Set<String> permissions = authorization.effectivePermissions(user.getRoleIds(), modules);
            return new PrincipalState(user.getStatus().name(), user.getTokenVersion(), tenant.status().name(),
                    permissions, modules);
        });
    }

    private static String key(UUID tenantId, UUID userId) {
        return TenantKeys.key(tenantId, "user", userId.toString(), "principal");
    }
}
