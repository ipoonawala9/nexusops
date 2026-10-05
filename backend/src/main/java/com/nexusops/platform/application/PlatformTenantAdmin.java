package com.nexusops.platform.application;

import com.nexusops.platform.security.CurrentPlatformActor;
import com.nexusops.shared.TenantContext;
import com.nexusops.tenancy.TenantDirectory;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Suspends/reactivates a workspace as the current platform operator. The tenant scope is opened around the
 * tenancy call only (so the audit row lands in that workspace's log); the read-back runs outside it, in
 * PlatformAccess, which refuses tenant scopes.
 */
@Service
public class PlatformTenantAdmin {

    private final TenantDirectory tenants;
    private final PlatformTenantQueries queries;

    PlatformTenantAdmin(TenantDirectory tenants, PlatformTenantQueries queries) {
        this.tenants = tenants;
        this.queries = queries;
    }

    public PlatformTenantView suspend(UUID tenantId, String reason) {
        UUID operator = CurrentPlatformActor.require().id();
        try (var scope = TenantContext.open(tenantId, null)) {
            tenants.suspendCurrent(operator, reason);
        }
        return queries.get(tenantId);
    }

    public PlatformTenantView reactivate(UUID tenantId, String reason) {
        UUID operator = CurrentPlatformActor.require().id();
        try (var scope = TenantContext.open(tenantId, null)) {
            tenants.reactivateCurrent(operator, reason);
        }
        return queries.get(tenantId);
    }
}
