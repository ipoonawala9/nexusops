package com.nexusops.platform.domain;

import com.nexusops.shared.db.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A platform refresh token. {@code expiresAt} is the family's absolute cap (login + 8 h): rotation copies it,
 * never extends it (decision 2, ADR-0007).
 */
@Entity
@Table(name = "platform_refresh_tokens")
public class PlatformRefreshToken extends BaseEntity {

    @Column(name = "platform_user_id", nullable = false)
    private UUID platformUserId;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "token_hash", nullable = false, columnDefinition = "bpchar")
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_reason")
    private PlatformRevokeReason revokeReason;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Column(name = "created_ip")
    private String createdIp;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PlatformRefreshToken() {}

    public static PlatformRefreshToken issue(UUID id, UUID platformUserId, UUID familyId, String tokenHash,
            Instant expiresAt, int tokenVersion, String ip, String userAgent) {
        PlatformRefreshToken token = new PlatformRefreshToken();
        token.initId(id);
        token.platformUserId = platformUserId;
        token.familyId = familyId;
        token.tokenHash = tokenHash;
        token.expiresAt = expiresAt;
        token.tokenVersion = tokenVersion;
        token.createdIp = ip;
        token.userAgent = userAgent == null || userAgent.length() <= 512 ? userAgent : userAgent.substring(0, 512);
        token.createdAt = Instant.now();
        return token;
    }

    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public boolean rotatedWithin(Duration grace, Instant now) {
        return revokeReason == PlatformRevokeReason.ROTATED && revokedAt != null && revokedAt.plus(grace).isAfter(now);
    }

    public void markRotated(UUID replacement, Instant now) {
        revokedAt = now;
        revokeReason = PlatformRevokeReason.ROTATED;
        replacedBy = replacement;
    }

    public UUID getPlatformUserId() { return platformUserId; }
    public UUID getFamilyId() { return familyId; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getTokenVersion() { return tokenVersion; }
    public PlatformRevokeReason getRevokeReason() { return revokeReason; }
}
