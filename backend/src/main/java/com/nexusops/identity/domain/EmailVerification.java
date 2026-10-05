package com.nexusops.identity.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_verifications")
public class EmailVerification extends TenantOwnedEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, columnDefinition = "bpchar")
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected EmailVerification() {}

    private EmailVerification(UUID id) {
        super(id);
    }

    public static EmailVerification issue(UUID id, UUID userId, String tokenHash, Instant expiresAt) {
        EmailVerification verification = new EmailVerification(id);
        verification.userId = userId;
        verification.tokenHash = tokenHash;
        verification.expiresAt = expiresAt;
        verification.createdAt = Instant.now();
        return verification;
    }

    public boolean isUsable(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    public void markUsed(Instant now) {
        usedAt = now;
    }

    public UUID getUserId() { return userId; }
}
