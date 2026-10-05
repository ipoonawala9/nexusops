package com.nexusops.identity.application;

import java.time.Instant;
import java.util.UUID;

public record AuthResult(UUID tenantId, UUID userId, String accessToken, Instant accessTokenExpiresAt,
        String refreshToken, Instant refreshTokenExpiresAt) {}
