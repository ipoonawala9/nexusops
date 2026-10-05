package com.nexusops.tenancy;

import java.util.UUID;

public record TenantSummary(UUID id, String slug, String name, TenantStatus status, String planCode) {}
