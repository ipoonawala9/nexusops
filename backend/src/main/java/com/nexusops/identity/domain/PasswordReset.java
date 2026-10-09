package com.nexusops.identity.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "password_resets")
public class PasswordReset extends TenantOwnedEntity {

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

    protected PasswordReset() {}

    private PasswordReset(UUID id) {
        super(id);
    }

    public static PasswordReset issue(UUID id, UUID userId, String tokenHash, Instant expiresAt) {
        PasswordReset reset = new PasswordReset(id);
        reset.userId = userId;
        reset.tokenHash = tokenHash;
        reset.expiresAt = expiresAt;
        reset.createdAt = Instant.now();
        return reset;
    }

    public boolean isUsable(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    public UUID getUserId() { return userId; }
}
