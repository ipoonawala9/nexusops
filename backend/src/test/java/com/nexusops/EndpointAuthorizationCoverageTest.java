package com.nexusops;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.shared.web.PublicEndpoints;
import com.nexusops.support.IntegrationTestSupport;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Claude Code rule §41.6: no endpoint without an authorization requirement. */
class EndpointAuthorizationCoverageTest extends IntegrationTestSupport {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Test
    void everyHandlerIsEitherPublicOrHasPreAuthorize() {
        List<String> violations = new ArrayList<>();
        Set<String> seenRoutes = new HashSet<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith("com.nexusops")) {
                return;
            }
            boolean secured = AnnotatedElementUtils.hasAnnotation(handler.getMethod(), PreAuthorize.class)
                    || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), PreAuthorize.class);
            var methods = info.getMethodsCondition().getMethods();
            for (String path : info.getPatternValues()) {
                if (methods.isEmpty()) {
                    if (!secured) violations.add("ANY " + path);
                    continue;
                }
                for (var method : methods) {
                    String route = method.name() + " " + path;
                    seenRoutes.add(route);
                    boolean isPublic = PublicEndpoints.ROUTES.contains(route);
                    if (isPublic == secured) {
                        violations.add(route + (secured ? " (public AND @PreAuthorize)" : " (no @PreAuthorize)"));
                    }
                }
            }
        });
        assertThat(violations).isEmpty();
        assertThat(seenRoutes).as("PublicEndpoints must not list routes that no longer exist")
                .containsAll(PublicEndpoints.ROUTES);
    }
}
