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
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Per-request principal state with a short Redis TTL.
 *
 * <p>Race-free invalidation: every entry has a user generation counter ({@code …:principal-gen}) and each
 * tenant has a tenant generation ({@code tenant:{t}:principal-gen}). Eviction increments the relevant
 * counter (and deletes entries); a request that loaded state from the database may only store it
 * if both generations it read BEFORE loading are still current, even if its entry did not exist yet. So a request that read the old token
 * version just before a logout-all/disable can never re-populate the cache with stale state.
 * Redis failures fall back to the database (correct, just slower).
 */
@Component
public class PrincipalStateCache {

    private static final Logger log = LoggerFactory.getLogger(PrincipalStateCache.class);
    static final Duration TTL = Duration.ofSeconds(60);
    static final Duration GENERATION_TTL = Duration.ofHours(1);

    private static final RedisScript<Long> STORE_IF_GENERATION = new DefaultRedisScript<>("""
            local userGen = redis.call('GET', KEYS[2]) or '0'
            local tenantGen = redis.call('GET', KEYS[3]) or '0'
            if userGen == ARGV[1] and tenantGen == ARGV[2] then
              redis.call('SET', KEYS[1], ARGV[3], 'PX', ARGV[4])
              return 1
            end
            return 0
            """, Long.class);

    private static final RedisScript<Long> BUMP_GENERATION = new DefaultRedisScript<>("""
            redis.call('INCR', KEYS[1])
            return redis.call('PEXPIRE', KEYS[1], ARGV[1])
            """, Long.class);

    private static final RedisScript<Long> BUMP_AND_DELETE = new DefaultRedisScript<>("""
            redis.call('INCR', KEYS[2])
            redis.call('PEXPIRE', KEYS[2], ARGV[1])
            return redis.call('DEL', KEYS[1])
            """, Long.class);

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
        String generationKey = generationKey(tenantId, userId);
        String tenantGenerationKey = tenantGenerationKey(tenantId);
        String seenGeneration = null;
        String seenTenantGeneration = null;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return json.readValue(cached, PrincipalState.class);
            }
            String generation = redis.opsForValue().get(generationKey);
            String tenantGeneration = redis.opsForValue().get(tenantGenerationKey);
            seenGeneration = generation == null ? "0" : generation;
            seenTenantGeneration = tenantGeneration == null ? "0" : tenantGeneration;
        } catch (RuntimeException e) {
            log.warn("Principal cache read failed; using database", e);
        }
        PrincipalState state = load(userId);
        if (seenGeneration != null) {
            try {
                storeIfGeneration(key, generationKey, seenGeneration, tenantGenerationKey, seenTenantGeneration,
                        json.writeValueAsString(state));
            } catch (RuntimeException e) {
                log.warn("Principal cache write failed", e);
            }
        }
        return state;
    }

    /** Best-effort: a Redis failure is logged, not thrown (entries expire within {@link #TTL} anyway). */
    public void evict(UUID tenantId, UUID userId) {
        try {
            bumpAndDelete(key(tenantId, userId));
        } catch (RuntimeException e) {
            log.warn("Principal cache eviction failed; entry expires within TTL", e);
        }
    }

    /** Evicts every principal entry of one tenant (and nothing else under its prefix, e.g. rate-limit buckets). */
    public void evictTenant(UUID tenantId) {
        try {
            // First: invalidates every in-flight load, including for entries SCAN will not find.
            redis.execute(BUMP_GENERATION, List.of(tenantGenerationKey(tenantId)),
                    String.valueOf(GENERATION_TTL.toMillis()));
        } catch (RuntimeException e) {
            log.warn("Tenant generation bump failed; entries expire within TTL", e);
            return;
        }
        String pattern = TenantKeys.key(tenantId, "user") + ":*:principal";
        try (Cursor<String> keys = redis.scan(ScanOptions.scanOptions().match(pattern).count(500).build())) {
            keys.forEachRemaining(key -> {
                try {
                    bumpAndDelete(key);
                } catch (RuntimeException e) {
                    log.warn("Principal cache entry eviction failed; entry expires within TTL", e);
                }
            });
        } catch (RuntimeException e) {
            log.warn("Tenant principal cache eviction failed; entries expire within TTL", e);
        }
    }

    boolean storeIfGeneration(String key, String generationKey, String seenGeneration,
            String tenantGenerationKey, String seenTenantGeneration, String value) {
        Long stored = redis.execute(STORE_IF_GENERATION, List.of(key, generationKey, tenantGenerationKey),
                seenGeneration, seenTenantGeneration, value, String.valueOf(TTL.toMillis()));
        return stored != null && stored == 1L;
    }

    private void bumpAndDelete(String key) {
        redis.execute(BUMP_AND_DELETE, List.of(key, key + "-gen"), String.valueOf(GENERATION_TTL.toMillis()));
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
                    permissions, modules, user.getRoleIds(), authorization.grantablePermissions(user.getRoleIds()));
        });
    }

    static String key(UUID tenantId, UUID userId) {
        return TenantKeys.key(tenantId, "user", userId.toString(), "principal");
    }

    static String generationKey(UUID tenantId, UUID userId) {
        return key(tenantId, userId) + "-gen";
    }

    static String tenantGenerationKey(UUID tenantId) {
        return TenantKeys.key(tenantId, "principal-gen");
    }
}
