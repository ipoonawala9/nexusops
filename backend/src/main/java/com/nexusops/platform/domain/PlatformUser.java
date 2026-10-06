package com.nexusops.platform.domain;

import com.nexusops.shared.db.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * A NexusOps staff account (spec §5). The table is visible only inside PlatformAccess. Changing the password or
 * the authenticator, or disabling the account, bumps {@code tokenVersion}, which ends every platform session.
 */
@Entity
@Table(name = "platform_users")
public class PlatformUser extends BaseEntity {

    /** Consecutive wrong TOTP codes (after a correct password) that disable the account (I1, ADR-0007). */
    public static final int MAX_FAILED_TOTP = 10;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "totp_secret_enc", nullable = false)
    private String totpSecretEnc;

    @Column(name = "totp_last_step", nullable = false)
    private long totpLastStep;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlatformRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlatformUserStatus status;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Column(name = "failed_totp_attempts", nullable = false)
    private int failedTotpAttempts;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected PlatformUser() {}

    /** {@code confirmedStep}: the step of the code that confirmed enrolment, consumed so it can't sign in again. */
    public static PlatformUser create(UUID id, String email, String passwordHash, String totpSecretEnc, PlatformRole role,
            long confirmedStep) {
        PlatformUser user = new PlatformUser();
        user.initId(id);
        user.email = email;
        user.passwordHash = passwordHash;
        user.totpSecretEnc = totpSecretEnc;
        user.role = role;
        user.totpLastStep = confirmedStep;
        user.status = PlatformUserStatus.ACTIVE;
        user.createdAt = Instant.now();
        user.updatedAt = user.createdAt;
        return user;
    }

    public void replaceTotp(String encryptedSecret, long confirmedStep) {
        totpSecretEnc = encryptedSecret;
        totpLastStep = confirmedStep;
        endSessions();
    }

    public void replacePassword(String newHash) {
        passwordHash = newHash;
        endSessions();
    }

    public void disable() {
        status = PlatformUserStatus.DISABLED;
        endSessions();
    }

    public void enable() {
        status = PlatformUserStatus.ACTIVE;
        failedTotpAttempts = 0;
        updatedAt = Instant.now();
    }

    /**
     * A wrong code after a correct password. Returns true when this attempt reached {@link #MAX_FAILED_TOTP}: the
     * account is then disabled and its sessions end, until an operator re-enables it from the CLI.
     */
    public boolean recordFailedTotp() {
        failedTotpAttempts++;
        updatedAt = Instant.now();
        if (failedTotpAttempts >= MAX_FAILED_TOTP && status == PlatformUserStatus.ACTIVE) {
            disable();
            return true;
        }
        return false;
    }

    /** Single use (RFC 6238 §5.2): callers verified {@code step > totpLastStep} under a row lock. */
    public void recordTotpUse(long step) {
        if (step <= totpLastStep) {
            throw new IllegalStateException("TOTP step already used");
        }
        totpLastStep = step;
        failedTotpAttempts = 0;
    }

    public void recordLogin(Instant now) {
        lastLoginAt = now;
        updatedAt = now;
    }

    private void endSessions() {
        tokenVersion++;
        updatedAt = Instant.now();
    }

    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getTotpSecretEnc() { return totpSecretEnc; }
    public long getTotpLastStep() { return totpLastStep; }
    public PlatformRole getRole() { return role; }
    public PlatformUserStatus getStatus() { return status; }
    public int getTokenVersion() { return tokenVersion; }
    public int getFailedTotpAttempts() { return failedTotpAttempts; }
}
