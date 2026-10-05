package com.nexusops.identity.application;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.identity.domain.RefreshToken;
import com.nexusops.identity.domain.RefreshTokenRepository;
import com.nexusops.identity.domain.RevokeReason;
import com.nexusops.identity.domain.User;
import com.nexusops.identity.domain.UserRepository;
import com.nexusops.identity.domain.UserStatus;
import com.nexusops.identity.security.AccessTokenService;
import com.nexusops.identity.security.CurrentUser;
import com.nexusops.identity.security.PrincipalStateCache;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.tenancy.TenantDirectory;
import com.nexusops.tenancy.TenantStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refresh-token rotation with family reuse detection (ADR-0003). A rotated token re-presented within
 * {@link #REUSE_GRACE} is treated as a benign multi-tab race (401, family kept); after that it is
 * theft: the whole family is revoked and the event audited.
 */
@Service
public class RefreshService {

    public static final Duration REUSE_GRACE = Duration.ofSeconds(10);
    static final String SESSION_EXPIRED = "Your session has expired. Please sign in again.";

    private record Outcome(AuthResult result, boolean reuseDetected) {}

    private final RefreshTokenRepository refreshTokens;
    private final UserRepository users;
    private final TenantDirectory tenants;
    private final AccessTokenService accessTokens;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final PrincipalStateCache principals;

    RefreshService(RefreshTokenRepository refreshTokens, UserRepository users, TenantDirectory tenants,
            AccessTokenService accessTokens, AuditService audit, TransactionTemplate tx,
            PrincipalStateCache principals) {
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.tenants = tenants;
        this.accessTokens = accessTokens;
        this.audit = audit;
        this.tx = tx;
        this.principals = principals;
    }

    public AuthResult refresh(String token, ClientInfo client) {
        OpaqueTokens.Parsed parsed = OpaqueTokens.parse(token).orElseThrow(RefreshService::expired);
        Outcome outcome;
        try (var scope = TenantContext.open(parsed.tenantId(), null)) {
            outcome = tx.execute(status -> rotate(parsed, client));
        }
        if (outcome == null || outcome.result() == null) {
            throw expired(); // includes reuse: the revocation above has been committed first
        }
        return outcome.result();
    }

    public void logout(String token) {
        OpaqueTokens.parse(token).ifPresent(parsed -> {
            try (var scope = TenantContext.open(parsed.tenantId(), null)) {
                tx.executeWithoutResult(status -> refreshTokens.findByTokenHash(parsed.hash()).ifPresent(existing -> {
                    refreshTokens.revokeFamily(existing.getFamilyId(), RevokeReason.LOGOUT, Instant.now());
                    audit.record(AuditEntry.of("Logout", "User", existing.getUserId()));
                }));
            }
        });
    }

    /** Invalidates every session of the current user: bumps token_version and revokes all refresh tokens. */
    public void logoutAll() {
        var current = CurrentUser.require();
        tx.executeWithoutResult(status -> {
            User user = users.findById(current.userId()).orElseThrow(RefreshService::expired);
            user.bumpTokenVersion();
            refreshTokens.revokeAllForUser(user.getId(), RevokeReason.LOGOUT_ALL, Instant.now());
            audit.record(AuditEntry.of("LogoutAll", "User", user.getId()));
        });
        principals.evict(current.tenantId(), current.userId());
    }

    private Outcome rotate(OpaqueTokens.Parsed parsed, ClientInfo client) {
        Instant now = Instant.now();
        RefreshToken current = refreshTokens.findForUpdateByTokenHash(parsed.hash()).orElse(null);
        if (current == null) {
            return new Outcome(null, false);
        }
        if (!current.isActive(now)) {
            if (current.getRevokeReason() == RevokeReason.ROTATED && !current.rotatedWithin(REUSE_GRACE, now)) {
                refreshTokens.revokeFamily(current.getFamilyId(), RevokeReason.REUSE_DETECTED, now);
                audit.record(AuditEntry.of("RefreshTokenReuseDetected", "User", current.getUserId())
                        .withMetadata(Map.of("familyId", current.getFamilyId().toString())));
                return new Outcome(null, true);
            }
            return new Outcome(null, false);
        }
        User user = users.findById(current.getUserId()).orElse(null);
        if (user != null && user.getTokenVersion() != current.getTokenVersion()) {
            // issued before a logout-all whose bulk revocation missed it (e.g. inserted by a concurrent refresh)
            refreshTokens.revokeFamily(current.getFamilyId(), RevokeReason.LOGOUT_ALL, now);
            return new Outcome(null, false);
        }
        if (user == null || user.getStatus() != UserStatus.ACTIVE || !user.isEmailVerified()
                || tenants.current().status() != TenantStatus.ACTIVE) {
            return new Outcome(null, false);
        }

        UUID tenantId = TenantContext.requireTenantId();
        String nextToken = OpaqueTokens.generate(tenantId);
        UUID nextId = Ids.newId();
        refreshTokens.save(RefreshToken.issue(nextId, user.getId(), current.getFamilyId(), OpaqueTokens.hash(nextToken),
                current.getExpiresAt(), user.getTokenVersion(), client.ip(), client.userAgent()));
        current.markRotated(nextId, now);
        var access = accessTokens.issue(tenantId, user.getId(), user.getTokenVersion());
        return new Outcome(new AuthResult(tenantId, user.getId(), access.value(), access.expiresAt(), nextToken,
                current.getExpiresAt()), false);
    }

    private static ApiProblem expired() {
        return ApiProblem.unauthorized(SESSION_EXPIRED);
    }
}
