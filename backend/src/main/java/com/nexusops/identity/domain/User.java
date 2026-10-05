package com.nexusops.identity.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User extends TenantOwnedEntity {

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** role ids only: identity does not depend on the authorization module's entities. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role_id")
    private Set<UUID> roleIds = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected User() {}

    private User(UUID id) {
        super(id);
    }

    /** The workspace creator: active immediately, but cannot log in until the email is verified. */
    public static User registerOwner(UUID id, String email, String passwordHash, String firstName, String lastName,
            UUID ownerRoleId) {
        User user = new User(id);
        user.email = email;
        user.passwordHash = passwordHash;
        user.firstName = firstName;
        user.lastName = lastName;
        user.status = UserStatus.ACTIVE;
        if (ownerRoleId != null) {
            user.roleIds.add(ownerRoleId);
        }
        user.createdAt = Instant.now();
        user.updatedAt = user.createdAt;
        return user;
    }

    /** A person who accepted an invitation: active, email proven by the invitation link, holding one role. */
    public static User joinFromInvitation(UUID id, String email, String passwordHash, String firstName, String lastName,
            UUID roleId, Instant now) {
        User user = registerOwner(id, email, passwordHash, firstName, lastName, roleId);
        user.markEmailVerified(now);
        return user;
    }

    public void markEmailVerified(Instant now) {
        if (emailVerifiedAt == null) {
            emailVerifiedAt = now;
            updatedAt = now;
        }
    }

    public void recordLogin(Instant now) {
        lastLoginAt = now;
    }

    public void bumpTokenVersion() {
        tokenVersion++;
        updatedAt = Instant.now();
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public UserStatus getStatus() { return status; }
    public int getTokenVersion() { return tokenVersion; }
    public Set<UUID> getRoleIds() { return Set.copyOf(roleIds); }
}
