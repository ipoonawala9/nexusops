package com.nexusops.identity.application;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.identity.domain.RefreshToken;
import com.nexusops.identity.domain.RefreshTokenRepository;
import com.nexusops.identity.domain.User;
import com.nexusops.identity.domain.UserRepository;
import com.nexusops.identity.domain.UserStatus;
import com.nexusops.identity.security.AccessTokenService;
import com.nexusops.identity.security.JwtProperties;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.tenancy.TenantDirectory;
import com.nexusops.tenancy.TenantStatus;
import com.nexusops.tenancy.TenantSummary;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Password login (spec §6): uniform failures, dummy-hash timing, audited outcomes. */
@Service
public class LoginService {

    static final String INVALID_CREDENTIALS = "Invalid workspace, email or password.";

    private final TenantDirectory tenants;
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokens;
    private final JwtProperties jwtProperties;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final String dummyHash;

    LoginService(TenantDirectory tenants, UserRepository users, RefreshTokenRepository refreshTokens,
            PasswordEncoder passwordEncoder, AccessTokenService accessTokens, JwtProperties jwtProperties,
            AuditService audit, TransactionTemplate tx) {
        this.tenants = tenants;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.accessTokens = accessTokens;
        this.jwtProperties = jwtProperties;
        this.audit = audit;
        this.tx = tx;
        this.dummyHash = passwordEncoder.encode("dummy password used to equalize timing");
    }

    public AuthResult login(String workspace, String rawEmail, String password, ClientInfo client) {
        Optional<TenantSummary> tenant = tenants.findBySlug(workspace);
        String email = normalizeOrNull(rawEmail);
        if (tenant.isEmpty() || email == null) {
            passwordEncoder.matches(password, dummyHash);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("workspace", truncate(workspace));
            metadata.put("reason", tenant.isEmpty() ? "UNKNOWN_WORKSPACE" : "INVALID_EMAIL");
            audit.recordIndependently(AuditEntry.of("LoginFailed", "Tenant", null).withMetadata(metadata));
            throw ApiProblem.unauthorized(INVALID_CREDENTIALS);
        }
        UUID tenantId = tenant.get().id();

        User user;
        try (var scope = TenantContext.open(tenantId, null)) {
            user = tx.execute(status -> users.findByEmail(email).orElse(null));
            if (user == null) {
                passwordEncoder.matches(password, dummyHash);
                throw fail("UNKNOWN_USER", null);
            }
            if (!passwordEncoder.matches(password, user.getPasswordHash())) {
                throw fail("BAD_PASSWORD", user.getId());
            }
            if (user.getStatus() != UserStatus.ACTIVE) {
                throw fail("USER_" + user.getStatus(), user.getId());
            }
            if (!user.isEmailVerified()) {
                throw ApiProblem.forbidden("Email address not verified.");
            }
            if (tenant.get().status() == TenantStatus.SUSPENDED) {
                fail("WORKSPACE_SUSPENDED", user.getId());
                throw ApiProblem.forbidden("Workspace suspended.");
            }
        }

        UUID userId = user.getId();
        try (var scope = TenantContext.open(tenantId, userId)) {
            return tx.execute(status -> {
                Instant now = Instant.now();
                User managed = users.findById(userId).orElseThrow(() -> ApiProblem.unauthorized(INVALID_CREDENTIALS));
                managed.recordLogin(now);
                String refreshToken = OpaqueTokens.generate(tenantId);
                Instant refreshExpiresAt = now.plus(jwtProperties.refreshTokenTtl());
                refreshTokens.save(RefreshToken.issue(Ids.newId(), userId, Ids.newId(), OpaqueTokens.hash(refreshToken),
                        refreshExpiresAt, client.ip(), client.userAgent()));
                audit.record(AuditEntry.of("LoginSucceeded", "User", userId));
                var access = accessTokens.issue(tenantId, userId, managed.getTokenVersion());
                return new AuthResult(tenantId, userId, access.value(), access.expiresAt(), refreshToken, refreshExpiresAt);
            });
        }
    }

    /** Audits a failed attempt (own transaction) and returns the generic 401 to throw. */
    private ApiProblem fail(String reason, UUID userId) {
        audit.recordIndependently(AuditEntry.of("LoginFailed", "User", userId).withMetadata(Map.of("reason", reason)));
        return ApiProblem.unauthorized(INVALID_CREDENTIALS);
    }

    private static String normalizeOrNull(String rawEmail) {
        try {
            return Emails.normalize(rawEmail);
        } catch (ApiProblem invalid) {
            return null;
        }
    }

    private static String truncate(String value) {
        if (value == null) return "";
        String trimmed = value.strip();
        return trimmed.length() <= 64 ? trimmed : trimmed.substring(0, 64);
    }
}
