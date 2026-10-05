package com.nexusops.identity.application;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.authorization.AuthorizationService;
import com.nexusops.identity.domain.EmailVerification;
import com.nexusops.identity.domain.EmailVerificationRepository;
import com.nexusops.identity.domain.User;
import com.nexusops.identity.domain.UserRepository;
import com.nexusops.identity.domain.UserStatus;
import com.nexusops.notifications.MailRequested;
import com.nexusops.notifications.OutgoingMail;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.tenancy.TenantDirectory;
import com.nexusops.tenancy.TenantSummary;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Self-serve workspace signup and email verification (spec §6). */
@Service
public class SignupService {

    public record SignupResult(UUID tenantId, UUID userId, String slug) {}

    private static final Duration VERIFICATION_TTL = Duration.ofHours(24);

    private final TenantDirectory tenants;
    private final AuthorizationService authorization;
    private final UserRepository users;
    private final EmailVerificationRepository verifications;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final String appBaseUrl;

    SignupService(TenantDirectory tenants, AuthorizationService authorization, UserRepository users,
            EmailVerificationRepository verifications, PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy,
            AuditService audit, ApplicationEventPublisher events, TransactionTemplate tx,
            @Value("${nexusops.app.base-url}") String appBaseUrl) {
        this.tenants = tenants;
        this.authorization = authorization;
        this.users = users;
        this.verifications = verifications;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.audit = audit;
        this.events = events;
        this.tx = tx;
        this.appBaseUrl = appBaseUrl;
    }

    public SignupResult signup(SignupCommand command) {
        String email = Emails.normalize(command.email());
        passwordPolicy.check(command.password(), email);
        String firstName = requireName(command.firstName(), "firstName");
        String lastName = requireName(command.lastName(), "lastName");
        String passwordHash = passwordEncoder.encode(command.password()); // slow: outside the transaction

        UUID tenantId = Ids.newId();
        UUID userId = Ids.newId();
        try (var scope = TenantContext.open(tenantId, userId)) {
            return tx.execute(status -> {
                TenantSummary tenant = tenants.register(tenantId, command.slug(), command.workspaceName());
                UUID ownerRole = authorization.createSystemRoles();
                users.save(User.registerOwner(userId, email, passwordHash, firstName, lastName, ownerRole));
                issueVerification(userId, email, firstName, tenant.slug());
                audit.record(AuditEntry.of("TenantCreated", "Tenant", tenantId)
                        .withAfter(Map.of("slug", tenant.slug(), "name", tenant.name())));
                audit.record(AuditEntry.of("UserRegistered", "User", userId).withAfter(Map.of("email", email)));
                return new SignupResult(tenantId, userId, tenant.slug());
            });
        }
    }

    public void verifyEmail(String token) {
        OpaqueTokens.Parsed parsed = OpaqueTokens.parse(token).orElseThrow(SignupService::invalidLink);
        Instant now = Instant.now();
        try (var scope = TenantContext.open(parsed.tenantId(), null)) {
            tx.executeWithoutResult(status -> {
                EmailVerification verification = verifications.findByTokenHash(parsed.hash())
                        .filter(v -> v.isUsable(now))
                        .orElseThrow(SignupService::invalidLink);
                verification.markUsed(now);
                User user = users.findById(verification.getUserId()).orElseThrow(SignupService::invalidLink);
                user.markEmailVerified(now);
                tenants.activateCurrent();
                audit.record(AuditEntry.of("EmailVerified", "User", user.getId()));
            });
        }
    }

    /** Always succeeds silently: never reveals whether the workspace or address exists. */
    public void resendVerification(String workspace, String rawEmail) {
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
                    .filter(u -> !u.isEmailVerified() && u.getStatus() == UserStatus.ACTIVE)
                    .ifPresent(user -> {
                        verifications.invalidateOpenForUser(user.getId(), Instant.now());
                        issueVerification(user.getId(), email, user.getFirstName(), tenant.get().slug());
                    }));
        }
    }

    private void issueVerification(UUID userId, String email, String firstName, String slug) {
        String token = OpaqueTokens.generate(TenantContext.requireTenantId());
        verifications.save(EmailVerification.issue(Ids.newId(), userId, OpaqueTokens.hash(token),
                Instant.now().plus(VERIFICATION_TTL)));
        String link = appBaseUrl + "/verify-email?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        events.publishEvent(new MailRequested(new OutgoingMail(email, "Verify your NexusOps workspace", """
                Hi %s,

                Confirm your email address to activate the workspace "%s":
                %s

                This link expires in 24 hours. If you didn't create this workspace, you can ignore this email.
                """.formatted(firstName, slug, link))));
    }

    private static String requireName(String raw, String field) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > 80) {
            throw ApiProblem.badRequestField(field, "Enter between 1 and 80 characters.");
        }
        return name;
    }

    private static ApiProblem invalidLink() {
        return ApiProblem.badRequest("This verification link is invalid or has expired.");
    }
}
