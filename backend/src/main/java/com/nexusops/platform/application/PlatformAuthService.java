package com.nexusops.platform.application;

import com.nexusops.audit.ActorType;
import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.platform.PlatformProperties;
import com.nexusops.platform.domain.PlatformRefreshToken;
import com.nexusops.platform.domain.PlatformRefreshTokenRepository;
import com.nexusops.platform.domain.PlatformRevokeReason;
import com.nexusops.platform.domain.PlatformUser;
import com.nexusops.platform.domain.PlatformUserRepository;
import com.nexusops.platform.domain.PlatformUserStatus;
import com.nexusops.platform.internal.PlatformAccess;
import com.nexusops.platform.security.PlatformSessionTokens;
import com.nexusops.platform.security.PlatformTokenService;
import com.nexusops.platform.totp.Totp;
import com.nexusops.platform.totp.TotpSecretCipher;
import com.nexusops.shared.Emails;
import com.nexusops.shared.Ids;
import com.nexusops.shared.web.ApiProblem;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Platform sign-in (spec §6, ADR-0007): password + single-use TOTP, uniform failures with dummy-hash timing,
 * rotating refresh tokens with reuse detection inside a fixed 8-hour family lifetime. Ten consecutive wrong codes
 * after a correct password disable the account (PlatformUser.MAX_FAILED_TOTP).
 */
@Service
public class PlatformAuthService {

    static final String INVALID = "Invalid email, password or code.";
    static final String EXPIRED = "Your session has expired. Please sign in again.";
    public static final Duration REUSE_GRACE = Duration.ofSeconds(10);

    public record Client(String ip, String userAgent) {}

    public record PlatformSession(String accessToken, Instant accessTokenExpiresAt, String refreshToken,
            Instant refreshTokenExpiresAt) {}

    private final PlatformAccess access;
    private final PlatformUserRepository users;
    private final PlatformRefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final TotpSecretCipher cipher;
    private final PlatformTokenService tokens;
    private final PlatformProperties properties;
    private final AuditService audit;
    private final String dummyHash;

    PlatformAuthService(PlatformAccess access, PlatformUserRepository users, PlatformRefreshTokenRepository refreshTokens,
            PasswordEncoder passwordEncoder, TotpSecretCipher cipher, PlatformTokenService tokens,
            PlatformProperties properties, AuditService audit) {
        this.access = access;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.cipher = cipher;
        this.tokens = tokens;
        this.properties = properties;
        this.audit = audit;
        this.dummyHash = passwordEncoder.encode("dummy platform password used to equalize timing");
    }

    public PlatformSession login(String rawEmail, String password, String code, Client client) {
        String email = Emails.tryNormalize(rawEmail).orElse(null);
        PlatformUser user = email == null ? null : access.read(() -> users.findByEmail(email).orElse(null));
        if (user == null) {
            passwordEncoder.matches(password, dummyHash);
            throw fail("UNKNOWN_USER", null);
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw fail("BAD_PASSWORD", user.getId());
        }
        if (user.getStatus() != PlatformUserStatus.ACTIVE) {
            throw fail("DISABLED", user.getId());
        }
        UUID id = user.getId();
        PlatformSession session = access.write(() -> {
            PlatformUser locked = users.findForUpdateById(id).orElse(null);
            if (locked == null || locked.getStatus() != PlatformUserStatus.ACTIVE) {
                return null;
            }
            Instant now = Instant.now();
            OptionalLong step = Totp.verify(cipher.decrypt(locked.getTotpSecretEnc(), id), code, now,
                    locked.getTotpLastStep());
            if (step.isEmpty()) {
                if (locked.recordFailedTotp()) {
                    audit.record(AuditEntry.of("PlatformUserLockedOut", "PlatformUser", id)
                            .withMetadata(Map.of("failedTotpAttempts", PlatformUser.MAX_FAILED_TOTP))
                            .asActor(ActorType.SYSTEM));
                }
                return null; // commits the count (and a lockout) before the uniform 401
            }
            locked.recordTotpUse(step.getAsLong());
            locked.recordLogin(now);
            String refreshToken = PlatformSessionTokens.generate();
            Instant cap = now.plus(properties.sessionMaxAge());
            refreshTokens.save(PlatformRefreshToken.issue(Ids.newId(), id, Ids.newId(),
                    PlatformSessionTokens.hash(refreshToken), cap, locked.getTokenVersion(), client.ip(), client.userAgent()));
            audit.record(AuditEntry.of("PlatformLoginSucceeded", "PlatformUser", id).asPlatformActor(id));
            var accessToken = tokens.issue(id, locked.getTokenVersion(), cap);
            return new PlatformSession(accessToken.value(), accessToken.expiresAt(), refreshToken, cap);
        });
        if (session == null) {
            throw fail("BAD_CODE", id);
        }
        return session;
    }

    public PlatformSession refresh(String token, Client client) {
        if (!PlatformSessionTokens.isWellFormed(token)) {
            throw ApiProblem.unauthorized(EXPIRED);
        }
        String hash = PlatformSessionTokens.hash(token);
        PlatformSession session = access.write(() -> rotate(hash, client));
        if (session == null) {
            throw ApiProblem.unauthorized(EXPIRED); // any revocation above has been committed first
        }
        return session;
    }

    public void logout(String token) {
        if (!PlatformSessionTokens.isWellFormed(token)) {
            return;
        }
        String hash = PlatformSessionTokens.hash(token);
        access.writeWithoutResult(() -> refreshTokens.findByTokenHash(hash).ifPresent(existing -> {
            refreshTokens.revokeFamily(existing.getFamilyId(), PlatformRevokeReason.LOGOUT, Instant.now());
            audit.record(AuditEntry.of("PlatformLogout", "PlatformUser", existing.getPlatformUserId())
                    .asPlatformActor(existing.getPlatformUserId()));
        }));
    }

    private PlatformSession rotate(String hash, Client client) {
        Instant now = Instant.now();
        PlatformRefreshToken current = refreshTokens.findForUpdateByTokenHash(hash).orElse(null);
        if (current == null) {
            return null;
        }
        if (!current.isActive(now)) {
            if (current.getRevokeReason() == PlatformRevokeReason.ROTATED && !current.rotatedWithin(REUSE_GRACE, now)) {
                refreshTokens.revokeFamily(current.getFamilyId(), PlatformRevokeReason.REUSE_DETECTED, now);
                audit.record(AuditEntry.of("PlatformRefreshTokenReuseDetected", "PlatformUser", current.getPlatformUserId())
                        .withMetadata(Map.of("familyId", current.getFamilyId().toString()))
                        .asPlatformActor(current.getPlatformUserId()));
            }
            return null;
        }
        PlatformUser user = users.findById(current.getPlatformUserId()).orElse(null);
        if (user == null || user.getStatus() != PlatformUserStatus.ACTIVE
                || user.getTokenVersion() != current.getTokenVersion()) {
            refreshTokens.revokeFamily(current.getFamilyId(), PlatformRevokeReason.REVOKED, now);
            return null;
        }
        String next = PlatformSessionTokens.generate();
        UUID nextId = Ids.newId();
        refreshTokens.save(PlatformRefreshToken.issue(nextId, user.getId(), current.getFamilyId(),
                PlatformSessionTokens.hash(next), current.getExpiresAt(), user.getTokenVersion(), client.ip(),
                client.userAgent()));
        current.markRotated(nextId, now);
        var accessToken = tokens.issue(user.getId(), user.getTokenVersion(), current.getExpiresAt());
        return new PlatformSession(accessToken.value(), accessToken.expiresAt(), next, current.getExpiresAt());
    }

    /** Audits a failed attempt in its own transaction and returns the uniform 401 to throw. */
    private ApiProblem fail(String reason, UUID platformUserId) {
        audit.recordIndependently(AuditEntry.of("PlatformLoginFailed", "PlatformUser", platformUserId)
                .withMetadata(Map.of("reason", reason))
                .asActor(ActorType.ANONYMOUS));
        return ApiProblem.unauthorized(INVALID);
    }
}
