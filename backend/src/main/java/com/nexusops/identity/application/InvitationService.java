package com.nexusops.identity.application;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.authorization.AuthorizationService;
import com.nexusops.identity.domain.Invitation;
import com.nexusops.identity.domain.InvitationRepository;
import com.nexusops.identity.domain.UserRepository;
import com.nexusops.identity.domain.UserStatus;
import com.nexusops.identity.security.CurrentUser;
import com.nexusops.notifications.MailRequested;
import com.nexusops.notifications.OutgoingMail;
import com.nexusops.shared.Ids;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.tenancy.TenantDirectory;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final String appBaseUrl;

    InvitationService(InvitationRepository invitations, UserRepository users, AuthorizationService authorization,
            TenantDirectory tenants, TenantLocks locks, AuditService audit, ApplicationEventPublisher events,
            @Value("${nexusops.app.base-url}") String appBaseUrl) {
        this.invitations = invitations;
        this.users = users;
        this.authorization = authorization;
        this.tenants = tenants;
        this.locks = locks;
        this.audit = audit;
        this.events = events;
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

    static InvitationView view(Invitation i, String roleName, Instant now) {
        return new InvitationView(i.getId(), i.getEmail(), i.getRoleId(), roleName, i.status(now).name(), i.getInvitedBy(),
                i.getExpiresAt(), i.getCreatedAt());
    }
}
