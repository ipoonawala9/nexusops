package com.nexusops.platform.security;

import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentPlatformActor {

    private CurrentPlatformActor() {}

    public static PlatformActor require() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getDetails() instanceof PlatformActor actor) {
            return actor;
        }
        throw new IllegalStateException("No authenticated platform operator");
    }
}
