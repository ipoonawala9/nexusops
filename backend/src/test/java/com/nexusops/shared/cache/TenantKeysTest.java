package com.nexusops.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantKeysTest {

    private final UUID tenant = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Test
    void buildsTenantPrefixedKeys() {
        assertThat(TenantKeys.key(tenant, "user", "abc", "principal"))
                .isEqualTo("tenant:00000000-0000-7000-8000-000000000001:user:abc:principal");
        assertThat(TenantKeys.tenantPattern(tenant)).isEqualTo("tenant:00000000-0000-7000-8000-000000000001:*");
    }

    @Test
    void rejectsMissingTenantOrUnsafeParts() {
        assertThatThrownBy(() -> TenantKeys.key(null, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TenantKeys.key(tenant)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantKeys.key(tenant, "a:b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantKeys.key(tenant, "*")).isInstanceOf(IllegalArgumentException.class);
    }
}
