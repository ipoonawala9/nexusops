package com.nexusops.tenancy;

import java.util.UUID;

/** Published inside the transaction that enabled or disabled a module for a tenant. */
public record ModulesChanged(UUID tenantId) {}
