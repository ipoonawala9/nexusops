package com.nexusops.authorization;

import java.util.UUID;

/** Published inside the transaction that changed a role's permissions or deleted a role. */
public record RolesChanged(UUID tenantId) {}
