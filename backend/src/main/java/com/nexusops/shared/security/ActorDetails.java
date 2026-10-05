package com.nexusops.shared.security;

import java.util.Set;
import java.util.UUID;

/** The authenticated caller's roles and grantable permissions (union of role permissions, module-agnostic). */
public record ActorDetails(Set<UUID> roleIds, Set<String> grantablePermissions) {}
