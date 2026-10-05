package com.nexusops.tenancy;

import java.util.UUID;

public record TenantSettings(UUID id, String slug, String name, TenantStatus status, String planCode,
        String timezone, String locale, String currency) {}
