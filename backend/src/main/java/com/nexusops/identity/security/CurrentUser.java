package com.nexusops.identity.security;

import com.nexusops.shared.TenantContext;
import java.util.UUID;

public record CurrentUser(UUID tenantId, UUID userId) {

    public static CurrentUser require() {
        return new CurrentUser(TenantContext.requireTenantId(),
                TenantContext.userId().orElseThrow(() -> new IllegalStateException("No authenticated user")));
    }
}
