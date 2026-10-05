package com.nexusops.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.authorization.RolesChanged;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.tenancy.ModulesChanged;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class PrincipalStateCacheIT extends IntegrationTestSupport {

    @Autowired PrincipalStateCache cache;
    @Autowired StringRedisTemplate redis;
    @Autowired ApplicationEventPublisher events;
    @Autowired TransactionTemplate tx;

    final UUID tenant = UUID.randomUUID();
    final UUID user = UUID.randomUUID();

    @Test
    void writeWithTheCurrentGenerationLands() {
        String key = PrincipalStateCache.key(tenant, user);
        assertThat(cache.storeIfGeneration(key, PrincipalStateCache.generationKey(tenant, user), "0", "{}")).isTrue();
        assertThat(redis.opsForValue().get(key)).isEqualTo("{}");
    }

    @Test
    void staleWriteAfterAnEvictionIsDiscarded() {
        String key = PrincipalStateCache.key(tenant, user);
        String seen = "0"; // generation read by a request before it loaded state from the DB
        cache.evict(tenant, user); // a concurrent logout-all / disable / role change
        assertThat(cache.storeIfGeneration(key, PrincipalStateCache.generationKey(tenant, user), seen, "{\"stale\":true}"))
                .isFalse();
        assertThat(redis.opsForValue().get(key)).isNull();
    }

    @Test
    void evictTenantRemovesOnlyThatTenantsPrincipalEntries() {
        UUID other = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        redis.opsForValue().set(PrincipalStateCache.key(tenant, user), "{}");
        redis.opsForValue().set(PrincipalStateCache.key(tenant, other), "{}");
        String rateLimitKey = "tenant:" + tenant + ":user:" + user + ":rl:api";
        redis.opsForValue().set(rateLimitKey, "5");
        redis.opsForValue().set(PrincipalStateCache.key(otherTenant, user), "{}");

        cache.evictTenant(tenant);

        assertThat(redis.hasKey(PrincipalStateCache.key(tenant, user))).isFalse();
        assertThat(redis.hasKey(PrincipalStateCache.key(tenant, other))).isFalse();
        assertThat(redis.opsForValue().get(PrincipalStateCache.generationKey(tenant, user))).isEqualTo("1");
        assertThat(redis.opsForValue().get(rateLimitKey)).isEqualTo("5");
        assertThat(redis.hasKey(PrincipalStateCache.key(otherTenant, user))).isTrue();
    }

    @Test
    void roleAndModuleEventsEvictTheTenantAfterCommit() {
        redis.opsForValue().set(PrincipalStateCache.key(tenant, user), "{}");
        tx.executeWithoutResult(s -> {
            events.publishEvent(new RolesChanged(tenant));
            assertThat(redis.hasKey(PrincipalStateCache.key(tenant, user))).as("not before commit").isTrue();
        });
        assertThat(redis.hasKey(PrincipalStateCache.key(tenant, user))).isFalse();

        redis.opsForValue().set(PrincipalStateCache.key(tenant, user), "{}");
        events.publishEvent(new ModulesChanged(tenant)); // no transaction: immediate
        assertThat(redis.hasKey(PrincipalStateCache.key(tenant, user))).isFalse();
    }
}
