package com.nexusops.platform.security;

import com.nexusops.shared.web.PublicEndpoints;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** A stale Authorization header must not break the public platform auth routes (login/refresh/logout). */
final class PlatformBearerTokenResolver implements BearerTokenResolver {

    private final BearerTokenResolver delegate = new DefaultBearerTokenResolver();
    private final RequestMatcher[] publicRoutes = PublicEndpoints.matchers();

    @Override
    public String resolve(HttpServletRequest request) {
        return Arrays.stream(publicRoutes).anyMatch(m -> m.matches(request)) ? null : delegate.resolve(request);
    }
}
