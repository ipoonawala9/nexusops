package com.nexusops.identity.web;

import com.nexusops.shared.web.ApiProblem;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Defence in depth for cookie-authenticated routes: a browser Origin, if sent, must be ours (threat T8). */
@Component
class OriginGuard {

    private final List<String> allowedOrigins;

    OriginGuard(@Value("${nexusops.security.allowed-origins}") List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins.stream().map(String::strip).toList();
    }

    void check(String origin) {
        if (origin != null && !allowedOrigins.contains(origin)) {
            throw ApiProblem.forbidden("Cross-site request rejected.");
        }
    }
}
