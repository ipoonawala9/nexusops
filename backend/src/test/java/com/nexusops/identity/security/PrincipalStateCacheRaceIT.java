package com.nexusops.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.nexusops.authorization.AuthorizationService;
import com.nexusops.shared.TenantContext;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.TestTenants;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/** Drives get() itself, with an eviction landing while the database load is in flight. */
@AutoConfigureMockMvc
class PrincipalStateCacheRaceIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired PrincipalStateCache cache;
    @Autowired StringRedisTemplate redis;
    @MockitoSpyBean AuthorizationService authorization;

    UUID tenant;
    UUID user;

    @BeforeEach
    void seed() throws Exception {
        var workspace = TestTenants.signup(mvc, TestTenants.uniqueSlug("race"));
        tenant = workspace.tenantId();
        user = OwnerJdbc.ownerAs(tenant).queryForObject("select id from users limit 1", UUID.class);
    }

    @AfterEach
    void resetSpy() {
        reset(authorization);
    }

    @Test
    void tenantEvictionDuringLoadPreventsCaching() {
        doAnswer(inv -> {
            cache.evictTenant(tenant); // e.g. a role was changed and committed while we load
            return inv.callRealMethod();
        }).when(authorization).effectivePermissions(any(), any());

        var state = TenantContext.callAs(tenant, () -> cache.get(tenant, user));

        assertThat(state).isNotNull();
        assertThat(redis.hasKey(PrincipalStateCache.key(tenant, user))).isFalse();
    }

    @Test
    void userEvictionDuringLoadPreventsCaching() {
        doAnswer(inv -> {
            cache.evict(tenant, user); // e.g. logout-all / disable committed while we load
            return inv.callRealMethod();
        }).when(authorization).effectivePermissions(any(), any());

        TenantContext.callAs(tenant, () -> cache.get(tenant, user));

        assertThat(redis.hasKey(PrincipalStateCache.key(tenant, user))).isFalse();
    }

    @Test
    void withoutInterleavedEvictionTheStateIsCached() {
        TenantContext.callAs(tenant, () -> cache.get(tenant, user));

        assertThat(redis.hasKey(PrincipalStateCache.key(tenant, user))).isTrue();
    }
}
