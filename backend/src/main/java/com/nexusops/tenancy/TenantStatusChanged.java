package com.nexusops.tenancy;

import java.util.UUID;

/** Published inside the transaction that suspended or reactivated a tenant. */
public record TenantStatusChanged(UUID tenantId) {}
