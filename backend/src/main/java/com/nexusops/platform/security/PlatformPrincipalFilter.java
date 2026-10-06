package com.nexusops.platform.security;

import com.nexusops.platform.domain.PlatformUser;
import com.nexusops.platform.domain.PlatformUserRepository;
import com.nexusops.platform.domain.PlatformUserStatus;
import com.nexusops.platform.internal.PlatformAccess;
import com.nexusops.shared.security.ProblemDetailSecurityHandlers;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs after platform JWT verification: loads the operator's live row (inside PlatformAccess) and rejects
 * unknown, disabled or stale-version principals. Authorities come from the stored role, never from the token.
 * No TenantContext is ever bound on platform requests.
 */
@Component
public class PlatformPrincipalFilter extends OncePerRequestFilter {

    public static final String STALE = "Your session is no longer valid. Please sign in again.";

    private final PlatformAccess access;
    private final PlatformUserRepository users;
    private final ProblemDetailSecurityHandlers problems;

    PlatformPrincipalFilter(PlatformAccess access, PlatformUserRepository users, ProblemDetailSecurityHandlers problems) {
        this.access = access;
        this.users = users;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
            chain.doFilter(request, response);
            return;
        }
        Jwt jwt = token.getToken();
        UUID id;
        int version;
        try {
            id = UUID.fromString(jwt.getSubject());
            version = ((Number) jwt.getClaim(PlatformTokenService.CLAIM_VERSION)).intValue();
        } catch (RuntimeException malformed) {
            reject(response);
            return;
        }
        PlatformUser user = access.read(() -> users.findById(id).orElse(null));
        if (user == null || user.getStatus() != PlatformUserStatus.ACTIVE || user.getTokenVersion() != version) {
            reject(response);
            return;
        }
        var authorities = user.getRole().authorities().stream().map(SimpleGrantedAuthority::new).toList();
        var authenticated = new JwtAuthenticationToken(jwt, authorities, id.toString());
        authenticated.setDetails(new PlatformActor(id, user.getEmail(), user.getRole()));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticated);
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        problems.write(response, HttpStatus.UNAUTHORIZED, "Unauthorized", STALE);
    }
}
