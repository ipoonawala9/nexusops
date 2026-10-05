package com.nexusops.identity.security;

import java.util.List;
import java.util.Set;

/** What every request needs to know about its caller, cached briefly in Redis (spec §6–§7). */
public record PrincipalState(String userStatus, int tokenVersion, String tenantStatus, Set<String> permissions,
        List<String> modules) {

    static PrincipalState missing() {
        return new PrincipalState("MISSING", -1, "MISSING", Set.of(), List.of());
    }
}
