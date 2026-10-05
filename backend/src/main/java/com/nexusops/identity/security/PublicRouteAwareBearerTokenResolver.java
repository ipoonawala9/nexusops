package com.nexusops.identity.security;

import com.nexusops.shared.web.PublicEndpoints;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** A stale/expired Authorization header must not break public routes such as /auth/refresh. */
class PublicRouteAwareBearerTokenResolver implements BearerTokenResolver {

    private final BearerTokenResolver delegate = new DefaultBearerTokenResolver();
    private final RequestMatcher[] publicRoutes = PublicEndpoints.matchers();

    @Override
    public String resolve(HttpServletRequest request) {
        boolean isPublic = Arrays.stream(publicRoutes).anyMatch(m -> m.matches(request));
        return isPublic ? null : delegate.resolve(request);
    }
}
