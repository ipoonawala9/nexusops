package com.nexusops.identity.security;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** What every request needs to know about its caller, cached briefly in Redis (spec §6–§7). */
public record PrincipalState(String userStatus, int tokenVersion, String tenantStatus, Set<String> permissions,
        List<String> modules, Set<UUID> roleIds, Set<String> grantablePermissions) {

    public PrincipalState {
        permissions = permissions == null ? Set.of() : permissions;
        modules = modules == null ? List.of() : modules;
        roleIds = roleIds == null ? Set.of() : roleIds;
        grantablePermissions = grantablePermissions == null ? Set.of() : grantablePermissions;
    }

    static PrincipalState missing() {
        return new PrincipalState("MISSING", -1, "MISSING", Set.of(), List.of(), Set.of(), Set.of());
    }
}
