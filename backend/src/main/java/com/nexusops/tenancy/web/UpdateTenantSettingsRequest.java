package com.nexusops.tenancy.web;

import jakarta.validation.constraints.Size;

public record UpdateTenantSettingsRequest(
        @Size(max = 120) String name,
        @Size(max = 64) String timezone,
        @Size(max = 35) String locale,
        @Size(min = 3, max = 3) String currency) {}
