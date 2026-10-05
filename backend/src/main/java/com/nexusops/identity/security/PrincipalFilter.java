package com.nexusops.identity.security;

import com.nexusops.shared.TenantContext;
import com.nexusops.shared.ratelimit.RateLimits;
import com.nexusops.shared.security.ProblemDetailSecurityHandlers;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs after JWT verification: binds TenantContext from the verified claims, then loads the
 * caller's live state (user status, token version, tenant status, permissions). Rejects stale
 * tokens and any tenant that is not ACTIVE before any controller runs; grants permissions as
 * authorities.
 */
@Component
public class PrincipalFilter extends OncePerRequestFilter {

    private final PrincipalStateCache principals;
    private final ProblemDetailSecurityHandlers problems;
    private final RateLimits rateLimits;

    PrincipalFilter(PrincipalStateCache principals, ProblemDetailSecurityHandlers problems, RateLimits rateLimits) {
        this.principals = principals;
        this.problems = problems;
        this.rateLimits = rateLimits;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            chain.doFilter(request, response);
            return;
        }
        Jwt jwt = token.getToken();
        UUID tenantId;
        UUID userId;
        int tokenVersion;
        try {
            tenantId = UUID.fromString(jwt.getClaimAsString(AccessTokenService.CLAIM_TENANT));
            userId = UUID.fromString(jwt.getSubject());
            tokenVersion = ((Number) jwt.getClaim(AccessTokenService.CLAIM_TOKEN_VERSION)).intValue();
        } catch (RuntimeException malformed) {
            reject(response, HttpStatus.UNAUTHORIZED, "Unauthorized", "Authentication is required.");
            return;
        }

        try (var scope = TenantContext.open(tenantId, userId)) {
            PrincipalState state = principals.get(tenantId, userId);
            if (!"ACTIVE".equals(state.userStatus()) || state.tokenVersion() != tokenVersion) {
                reject(response, HttpStatus.UNAUTHORIZED, "Unauthorized", "Your session is no longer valid. Please sign in again.");
                return;
            }
            if (!"ACTIVE".equals(state.tenantStatus())) { // allow-list: any non-active workspace fails closed
                reject(response, HttpStatus.FORBIDDEN, "Forbidden",
                        "SUSPENDED".equals(state.tenantStatus()) ? "Workspace suspended." : "Workspace is not active.");
                return;
            }
            var retryAfter = rateLimits.apiRetryAfter(tenantId, userId);
            if (retryAfter.isPresent()) {
                response.setHeader(org.springframework.http.HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter.getAsLong()));
                reject(response, HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                        "Too many requests. Try again in " + retryAfter.getAsLong() + " seconds.");
                return;
            }
            List<SimpleGrantedAuthority> authorities = state.permissions().stream().map(SimpleGrantedAuthority::new).toList();
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new JwtAuthenticationToken(jwt, authorities, userId.toString()));
            SecurityContextHolder.setContext(context);
            chain.doFilter(request, response);
        }
    }

    private void reject(HttpServletResponse response, HttpStatus status, String title, String detail) throws IOException {
        SecurityContextHolder.clearContext();
        problems.write(response, status, title, detail);
    }
}
