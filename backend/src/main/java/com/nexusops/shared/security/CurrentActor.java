package com.nexusops.shared.security;

import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentActor {

    private CurrentActor() {}

    public static ActorDetails require() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getDetails() instanceof ActorDetails details) {
            return details;
        }
        throw new IllegalStateException("No authenticated actor");
    }
}
