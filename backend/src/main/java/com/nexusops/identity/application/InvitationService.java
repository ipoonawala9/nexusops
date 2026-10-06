package com.nexusops.identity.application;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.authorization.AuthorizationService;
import com.nexusops.identity.domain.Invitation;
import com.nexusops.identity.domain.InvitationRepository;
import com.nexusops.identity.domain.User;
import com.nexusops.identity.domain.UserRepository;
import com.nexusops.identity.domain.UserStatus;
import com.nexusops.identity.security.CurrentUser;
import com.nexusops.notifications.MailRequested;
import com.nexusops.notifications.OutgoingMail;
import com.nexusops.shared.Emails;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.security.PasswordPolicy;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.tenancy.TenantDirectory;
import com.nexusops.tenancy.TenantStatus;
import com.nexusops.tenancy.TenantSummary;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Invitations: the only way (besides signup) a person joins a workspace. */
@Service
public class InvitationService {

    public static final Duration INVITATION_TTL = Duration.ofDays(7);

    private final InvitationRepository invitations;
    private final UserRepository users;
    private final AuthorizationService authorization;
    private final TenantDirectory tenants;
    private final TenantLocks locks;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate tx;
    private final String appBaseUrl;

    InvitationService(InvitationRepository invitations, UserRepository users, AuthorizationService authorization,
            TenantDirectory tenants, TenantLocks locks, AuditService audit, ApplicationEventPublisher events,
            PasswordPolicy passwordPolicy, PasswordEncoder passwordEncoder, TransactionTemplate tx,
            @Value("${nexusops.app.base-url}") String appBaseUrl) {
        this.invitations = invitations;
        this.users = users;
        this.authorization = authorization;
        this.tenants = tenants;
        this.locks = locks;
        this.audit = audit;
        this.events = events;
        this.passwordPolicy = passwordPolicy;
        this.passwordEncoder = passwordEncoder;
        this.tx = tx;
        this.appBaseUrl = appBaseUrl;
    }

    @Transactional
    public InvitationView invite(String rawEmail, UUID roleId) {
        CurrentUser actor = CurrentUser.require();
        String email = Emails.normalize(rawEmail);
        authorization.checkGrantable(List.of(roleId));
        locks.lock("seats");
        if (users.existsByEmail(email)) {
            throw ApiProblem.conflictField("email", "This person is already a member of the workspace.");
        }
        Instant now = Instant.now();
        invitations.findOpenByEmailForUpdate(email).ifPresent(open -> {
            if (open.isPending(now)) {
                throw ApiProblem.conflictField("email", "An invitation is already pending for this email.");
            }
            open.revoke(now); // expired: close it so the partial unique index admits the new one
            invitations.flush();
        });
        Integer maxUsers = tenants.currentLimits().maxUsers();
        if (maxUsers != null && users.countByStatus(UserStatus.ACTIVE) + invitations.countPending(now) >= maxUsers) {
            throw ApiProblem.conflict("Your plan allows " + maxUsers + " users. Upgrade to add more.");
        }

        String token = OpaqueTokens.generate(actor.tenantId());
        Invitation invitation = Invitation.issue(Ids.newId(), email, roleId, OpaqueTokens.hash(token), actor.userId(),
                now.plus(INVITATION_TTL));
        invitations.save(invitation);
        String roleName = authorization.roleNames(List.of(roleId)).get(roleId);
        String workspace = tenants.current().name();
        audit.record(AuditEntry.of("InvitationCreated", "Invitation", invitation.getId())
                .withAfter(Map.of("email", email, "role", roleName)));
        String link = appBaseUrl + "/invite/accept?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        events.publishEvent(new MailRequested(new OutgoingMail(email, "You're invited to join " + workspace + " on NexusOps", """
                Hello,

                You've been invited to join the workspace "%s" on NexusOps as %s.
                Accept the invitation and set your password here:
                %s

                This link expires in 7 days. If you weren't expecting this, you can ignore this email.
                """.formatted(workspace, roleName, link))));
        return view(invitation, roleName, now);
    }

    @Transactional(readOnly = true)
    public List<InvitationView> list() {
        CurrentUser.require();
        Instant now = Instant.now();
        List<Invitation> all = invitations.findAllByOrderByCreatedAtDesc();
        Map<UUID, String> roleNames = authorization.roleNames(all.stream().map(Invitation::getRoleId).toList());
        return all.stream().map(i -> view(i, roleNames.get(i.getRoleId()), now)).toList();
    }

    @Transactional
    public void revoke(UUID id) {
        CurrentUser.require();
        Instant now = Instant.now();
        Invitation invitation = invitations.findForUpdateById(id).orElseThrow(() -> ApiProblem.notFound("Invitation not found."));
        if (!invitation.isPending(now)) {
            throw ApiProblem.conflict("This invitation is no longer pending.");
        }
        invitation.revoke(now);
        audit.record(AuditEntry.of("InvitationRevoked", "Invitation", id).withBefore(Map.of("email", invitation.getEmail())));
    }

    static final String INVALID_LINK = "This invitation link is invalid or has expired.";

    /** Public: what the invitee is about to accept. */
    public InvitationPreview preview(String token) {
        OpaqueTokens.Parsed parsed = OpaqueTokens.parse(token).orElseThrow(InvitationService::invalidLink);
        try (var scope = TenantContext.open(parsed.tenantId(), null)) {
            return tx.execute(status -> {
                Invitation invitation = pendingInvitation(parsed.hash());
                TenantSummary tenant = activeTenant();
                String roleName = authorization.roleNames(List.of(invitation.getRoleId())).get(invitation.getRoleId());
                return new InvitationPreview(tenant.slug(), tenant.name(), invitation.getEmail(), roleName,
                        invitation.getExpiresAt());
            });
        }
    }

    /** Public: creates the member. The invitation row lock makes concurrent accepts create exactly one user. */
    public AcceptedInvitation accept(String token, String rawFirstName, String rawLastName, String password) {
        OpaqueTokens.Parsed parsed = OpaqueTokens.parse(token).orElseThrow(InvitationService::invalidLink);
        String email;
        try (var scope = TenantContext.open(parsed.tenantId(), null)) {
            email = tx.execute(status -> {
                String invitedEmail = pendingInvitation(parsed.hash()).getEmail(); // token first: bad tokens get the uniform 400
                activeTenant();
                return invitedEmail;
            });
        }
        passwordPolicy.check(password, email);
        String firstName = Names.require(rawFirstName, "firstName");
        String lastName = Names.require(rawLastName, "lastName");
        String passwordHash = passwordEncoder.encode(password); // slow: outside any transaction

        UUID userId = Ids.newId();
        try (var scope = TenantContext.open(parsed.tenantId(), userId)) {
            return tx.execute(status -> {
                Instant now = Instant.now();
                Invitation invitation = invitations.findForUpdateByTokenHash(parsed.hash())
                        .filter(i -> i.isPending(now))
                        .orElseThrow(InvitationService::invalidLink);
                if (users.existsByEmail(email)) {
                    throw ApiProblem.conflictField("email", "This person is already a member of the workspace.");
                }
                users.save(User.joinFromInvitation(userId, email, passwordHash, firstName, lastName,
                        invitation.getRoleId(), now));
                invitation.accept(now, userId);
                audit.record(AuditEntry.of("InvitationAccepted", "Invitation", invitation.getId()));
                audit.record(AuditEntry.of("UserRegistered", "User", userId)
                        .withAfter(Map.of("email", email, "via", "invitation")));
                return new AcceptedInvitation(tenants.current().slug(), email);
            });
        }
    }

    private Invitation pendingInvitation(String tokenHash) {
        Instant now = Instant.now();
        return invitations.findByTokenHash(tokenHash).filter(i -> i.isPending(now)).orElseThrow(InvitationService::invalidLink);
    }

    private TenantSummary activeTenant() {
        TenantSummary tenant = tenants.current();
        if (tenant.status() == TenantStatus.SUSPENDED) {
            throw ApiProblem.forbidden("Workspace suspended.");
        }
        if (tenant.status() != TenantStatus.ACTIVE) {
            throw invalidLink();
        }
        return tenant;
    }

    private static ApiProblem invalidLink() {
        return ApiProblem.badRequest(INVALID_LINK);
    }

    static InvitationView view(Invitation i, String roleName, Instant now) {
        return new InvitationView(i.getId(), i.getEmail(), i.getRoleId(), roleName, i.status(now).name(), i.getInvitedBy(),
                i.getExpiresAt(), i.getCreatedAt());
    }
}
