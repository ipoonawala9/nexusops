package com.nexusops.identity.application;

import java.time.Instant;
import java.util.UUID;

public record InvitationView(UUID id, String email, UUID roleId, String roleName, String status, UUID invitedBy,
        Instant expiresAt, Instant createdAt) {}
