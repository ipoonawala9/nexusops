package com.nexusops.identity.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken extends TenantOwnedEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

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
    private RevokeReason revokeReason;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    /** The user's token_version when this token was issued; a later logout-all invalidates it. */
    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Column(name = "created_ip")
    private String createdIp;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RefreshToken() {}

    private RefreshToken(UUID id) {
        super(id);
    }

    public static RefreshToken issue(UUID id, UUID userId, UUID familyId, String tokenHash, Instant expiresAt,
            int tokenVersion, String ip, String userAgent) {
        RefreshToken token = new RefreshToken(id);
        token.userId = userId;
        token.tokenVersion = tokenVersion;
        token.familyId = familyId;
        token.tokenHash = tokenHash;
        token.expiresAt = expiresAt;
        token.createdIp = ip;
        token.userAgent = userAgent == null || userAgent.length() <= 512 ? userAgent : userAgent.substring(0, 512);
        token.createdAt = Instant.now();
        return token;
    }

    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    /** True if this token was rotated (not revoked for another reason) less than {@code grace} ago. */
    public boolean rotatedWithin(Duration grace, Instant now) {
        return revokeReason == RevokeReason.ROTATED && revokedAt != null && revokedAt.plus(grace).isAfter(now);
    }

    public void markRotated(UUID replacement, Instant now) {
        this.revokedAt = now;
        this.revokeReason = RevokeReason.ROTATED;
        this.replacedBy = replacement;
    }

    public UUID getUserId() { return userId; }
    public UUID getFamilyId() { return familyId; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getTokenVersion() { return tokenVersion; }
    public RevokeReason getRevokeReason() { return revokeReason; }
}
