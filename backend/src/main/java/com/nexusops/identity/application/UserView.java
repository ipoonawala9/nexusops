package com.nexusops.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserView(UUID id, String email, String firstName, String lastName, String status, boolean emailVerified,
        List<RoleRef> roles, Instant lastLoginAt, Instant createdAt) {

    public record RoleRef(UUID id, String name) {}
}
