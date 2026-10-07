package com.nexusops.tenancy;

import java.util.UUID;

/**
 * Published by {@link TenantDirectory#register} inside the signup transaction, with the new workspace bound to
 * TenantContext (spec D13). Listeners seed per-workspace defaults synchronously, in that transaction.
 */
public record WorkspaceRegistered(UUID tenantId) {}
