package com.nexusops.platform.application;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.platform.security.CurrentPlatformActor;
import com.nexusops.shared.TenantContext;
import com.nexusops.tenancy.TenantDirectory;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Suspends/reactivates a workspace as the current platform operator. The tenant scope is opened around the
 * tenancy call only (so the audit row lands in that workspace's log, without the operator's IP and user agent);
 * the read-back runs outside it, in PlatformAccess, which refuses tenant scopes. A companion platform row
 * ({@code tenant_id} NULL) keeps the operator's full request details for staff forensics (ADR-0007).
 */
@Service
public class PlatformTenantAdmin {

    private final TenantDirectory tenants;
    private final PlatformTenantQueries queries;
    private final AuditService audit;

    PlatformTenantAdmin(TenantDirectory tenants, PlatformTenantQueries queries, AuditService audit) {
        this.tenants = tenants;
        this.queries = queries;
        this.audit = audit;
    }

    public PlatformTenantView suspend(UUID tenantId, String reason) {
        UUID operator = CurrentPlatformActor.require().id();
        try (var scope = TenantContext.open(tenantId, null)) {
            tenants.suspendCurrent(operator, reason);
        }
        recordPlatformRow("PlatformTenantSuspended", tenantId, reason, operator);
        return queries.get(tenantId);
    }

    public PlatformTenantView reactivate(UUID tenantId, String reason) {
        UUID operator = CurrentPlatformActor.require().id();
        try (var scope = TenantContext.open(tenantId, null)) {
            tenants.reactivateCurrent(operator, reason);
        }
        recordPlatformRow("PlatformTenantReactivated", tenantId, reason, operator);
        return queries.get(tenantId);
    }

    /** Outside the tenant scope and any transaction: tenant_id NULL, with the operator's IP and user agent. */
    private void recordPlatformRow(String action, UUID tenantId, String reason, UUID operator) {
        audit.recordIndependently(AuditEntry.of(action, "Tenant", tenantId)
                .withMetadata(Map.of("tenantId", tenantId.toString(), "reason", reason.strip()))
                .asPlatformActor(operator));
    }
}
