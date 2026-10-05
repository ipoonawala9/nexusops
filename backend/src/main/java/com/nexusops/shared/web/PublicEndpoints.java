package com.nexusops.shared.web;

import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The ONLY unauthenticated API routes. Security config permits exactly these, and
 * EndpointAuthorizationCoverageTest requires every other handler to declare @PreAuthorize.
 */
public final class PublicEndpoints {

    public static final Set<String> ROUTES = Set.of(
            "POST /api/v1/auth/signup",
            "POST /api/v1/auth/verify-email",
            "POST /api/v1/auth/resend-verification",
            "POST /api/v1/auth/login",
            "POST /api/v1/auth/refresh",
            "POST /api/v1/auth/logout",
            "GET /api/v1/invitations/preview",
            "POST /api/v1/invitations/accept");

    private PublicEndpoints() {}

    public static RequestMatcher[] matchers() {
        return ROUTES.stream()
                .map(route -> route.split(" ", 2))
                .map(parts -> PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.valueOf(parts[0]), parts[1]))
                .toArray(RequestMatcher[]::new);
    }
}
