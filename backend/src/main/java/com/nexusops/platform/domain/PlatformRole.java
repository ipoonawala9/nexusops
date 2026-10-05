package com.nexusops.platform.domain;

import java.util.Set;

/** Platform roles (ADR-0007). Authorities are resolved from the stored role on every request, never from a token. */
public enum PlatformRole {
    PLATFORM_ADMIN(Set.of("platform.tenant.read", "platform.tenant.suspend")),
    PLATFORM_SUPPORT(Set.of("platform.tenant.read"));

    private final Set<String> authorities;

    PlatformRole(Set<String> authorities) {
        this.authorities = authorities;
    }

    public Set<String> authorities() {
        return authorities;
    }
}
