package com.nexusops.identity.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexusops.shared.Ids;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Eviction is best-effort: a Redis outage must not fail the caller (e.g. logout-all after its commit). */
class PrincipalStateCacheTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final PrincipalStateCache cache = new PrincipalStateCache(redis, null, null, null, null, null);
    private final UUID tenantId = Ids.newId();

    @Test
    void evictToleratesRedisFailure() {
        when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("redis down"));
        assertThatCode(() -> cache.evict(tenantId, Ids.newId())).doesNotThrowAnyException();
        verify(redis).delete(anyString());
    }

    @Test
    void evictTenantToleratesRedisFailure() {
        when(redis.scan(any(ScanOptions.class))).thenThrow(new RedisConnectionFailureException("redis down"));
        assertThatCode(() -> cache.evictTenant(tenantId)).doesNotThrowAnyException();
        verify(redis).scan(any(ScanOptions.class));
    }
}
