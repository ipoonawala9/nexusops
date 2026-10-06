package com.nexusops.platform.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PlatformTenantView(UUID id, String slug, String name, String status, String planCode, Instant createdAt,
        long activeUsers, List<String> ownerEmails) {}
