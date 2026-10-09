package com.nexusops.identity.application;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.identity.domain.PasswordReset;
import com.nexusops.identity.domain.PasswordResetRepository;
import com.nexusops.identity.domain.RefreshTokenRepository;
import com.nexusops.identity.domain.RevokeReason;
import com.nexusops.identity.domain.User;
import com.nexusops.identity.domain.UserRepository;
import com.nexusops.identity.domain.UserStatus;
import com.nexusops.identity.security.PrincipalStateCache;
import com.nexusops.notifications.MailRequested;
import com.nexusops.notifications.OutgoingMail;
import com.nexusops.shared.Emails;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.security.PasswordPolicy;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.tenancy.TenantDirectory;
import com.nexusops.tenancy.TenantSummary;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * "Forgot password" for workspace users: an emailed one-time link that sets a new password and signs
 * the user out everywhere. Requesting a link never reveals whether the workspace or address exists.
 */
@Service
public class PasswordResetService {

    private static final Duration RESET_TTL = Duration.ofHours(1);

    private record Candidate(UUID linkId, UUID userId, String email) {}

    private final TenantDirectory tenants;
    private final UserRepository users;
    private final PasswordResetRepository resets;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final PrincipalStateCache principals;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final String appBaseUrl;

    PasswordResetService(TenantDirectory tenants, UserRepository users, PasswordResetRepository resets,
            RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy,
            PrincipalStateCache principals, AuditService audit, ApplicationEventPublisher events,
            TransactionTemplate tx, @Value("${nexusops.app.base-url}") String appBaseUrl) {
        this.tenants = tenants;
        this.users = users;
        this.resets = resets;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.principals = principals;
        this.audit = audit;
        this.events = events;
        this.tx = tx;
        this.appBaseUrl = appBaseUrl;
    }

    /** Always succeeds silently; only an ACTIVE user with a verified address is mailed a link. */
    public void request(String workspace, String rawEmail) {
        Optional<TenantSummary> tenant = tenants.findBySlug(workspace);
        if (tenant.isEmpty()) {
            return;
        }
        String email;
        try {
            email = Emails.normalize(rawEmail);
        } catch (ApiProblem invalid) {
            return;
        }
        try (var scope = TenantContext.open(tenant.get().id(), null)) {
            tx.executeWithoutResult(status -> users.findByEmail(email)
                    .filter(u -> u.getStatus() == UserStatus.ACTIVE && u.isEmailVerified())
                    .ifPresent(user -> {
                        Instant now = Instant.now();
                        resets.invalidateOpenForUser(user.getId(), now);
                        issue(user, now);
                        audit.record(AuditEntry.of("PasswordResetRequested", "User", user.getId()));
                    }));
        }
    }

    /** Sets the new password with a link from {@link #request}; every failure of the link is the same 400. */
    public void reset(String token, String newPassword) {
        OpaqueTokens.Parsed parsed = OpaqueTokens.parse(token).orElseThrow(PasswordResetService::invalidLink);
        UUID tenantId = parsed.tenantId();
        UUID userId;
        try (var scope = TenantContext.open(tenantId, null)) {
            Candidate candidate = tx.execute(status -> find(parsed.hash(), Instant.now()))
                    .orElseThrow(PasswordResetService::invalidLink);
            passwordPolicy.check(newPassword, candidate.email());
            String passwordHash = passwordEncoder.encode(newPassword); // slow: outside the transaction

            userId = candidate.userId();
            tx.executeWithoutResult(status -> {
                Instant now = Instant.now();
                if (resets.consume(candidate.linkId(), now) != 1) { // a concurrent redemption won
                    throw invalidLink();
                }
                resets.invalidateOpenForUser(candidate.userId(), now);
                User user = users.findById(candidate.userId()).filter(PasswordResetService::canReset)
                        .orElseThrow(PasswordResetService::invalidLink);
                user.changePassword(passwordHash);
                refreshTokens.revokeAllForUser(user.getId(), RevokeReason.PASSWORD_RESET, now);
                audit.record(AuditEntry.of("PasswordReset", "User", user.getId()));
            });
        }
        principals.evict(tenantId, userId);
    }

    private Optional<Candidate> find(String tokenHash, Instant now) {
        return resets.findByTokenHash(tokenHash)
                .filter(link -> link.isUsable(now))
                .flatMap(link -> users.findById(link.getUserId())
                        .filter(PasswordResetService::canReset)
                        .map(user -> new Candidate(link.getId(), user.getId(), user.getEmail())));
    }

    private static boolean canReset(User user) {
        return user.getStatus() == UserStatus.ACTIVE && user.isEmailVerified();
    }

    private void issue(User user, Instant now) {
        String token = OpaqueTokens.generate(TenantContext.requireTenantId());
        resets.save(PasswordReset.issue(Ids.newId(), user.getId(), OpaqueTokens.hash(token), now.plus(RESET_TTL)));
        String link = appBaseUrl + "/reset-password?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        events.publishEvent(new MailRequested(new OutgoingMail(user.getEmail(), "Reset your NexusOps password", """
                Hi %s,

                Someone asked to reset the password of your NexusOps account. To choose a new one, open:
                %s

                This link expires in 1 hour and works once. If you didn't ask for this, ignore this email — your password stays the same.
                """.formatted(user.getFirstName(), link))));
    }

    private static ApiProblem invalidLink() {
        return ApiProblem.badRequest("This reset link is invalid or has expired.");
    }
}
