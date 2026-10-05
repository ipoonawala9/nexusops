package com.nexusops.identity.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invitations")
public class Invitation extends TenantOwnedEntity {

    @Column(nullable = false)
    private String email;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(name = "token_hash", nullable = false, columnDefinition = "bpchar")
    private String tokenHash;

    @Column(name = "invited_by")
    private UUID invitedBy;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accepted_user_id")
    private UUID acceptedUserId;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Invitation() {}

    private Invitation(UUID id) {
        super(id);
    }

    public static Invitation issue(UUID id, String email, UUID roleId, String tokenHash, UUID invitedBy, Instant expiresAt) {
        Invitation invitation = new Invitation(id);
        invitation.email = email;
        invitation.roleId = roleId;
        invitation.tokenHash = tokenHash;
        invitation.invitedBy = invitedBy;
        invitation.expiresAt = expiresAt;
        invitation.createdAt = Instant.now();
        return invitation;
    }

    public InvitationStatus status(Instant now) {
        if (acceptedAt != null) return InvitationStatus.ACCEPTED;
        if (revokedAt != null) return InvitationStatus.REVOKED;
        if (!expiresAt.isAfter(now)) return InvitationStatus.EXPIRED;
        return InvitationStatus.PENDING;
    }

    public boolean isPending(Instant now) {
        return status(now) == InvitationStatus.PENDING;
    }

    /** Also used to close an expired invitation before re-inviting the same address. */
    public void revoke(Instant now) {
        this.revokedAt = now;
    }

    public void accept(Instant now, UUID userId) {
        this.acceptedAt = now;
        this.acceptedUserId = userId;
    }

    public String getEmail() { return email; }
    public UUID getRoleId() { return roleId; }
    public UUID getInvitedBy() { return invitedBy; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
}
