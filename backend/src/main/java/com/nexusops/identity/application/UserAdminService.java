package com.nexusops.identity.application;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.authorization.AuthorizationService;
import com.nexusops.identity.domain.InvitationRepository;
import com.nexusops.identity.domain.RefreshTokenRepository;
import com.nexusops.identity.domain.RevokeReason;
import com.nexusops.identity.domain.User;
import com.nexusops.identity.domain.UserRepository;
import com.nexusops.identity.domain.UserStatus;
import com.nexusops.identity.security.CurrentUser;
import com.nexusops.identity.security.PrincipalStateCache;
import com.nexusops.shared.db.AfterCommit;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.security.CurrentAuthorities;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.shared.web.PageResponse;
import com.nexusops.shared.web.Paging;
import com.nexusops.tenancy.TenantDirectory;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant user administration. Owner-set changes are serialized per tenant (TenantLocks "owners"). */
@Service
public class UserAdminService {

    static final String LAST_OWNER = "A workspace needs at least one active owner.";
    static final String FORBIDDEN = "You do not have permission to perform this action.";

    private final UserRepository users;
    private final InvitationRepository invitations;
    private final RefreshTokenRepository refreshTokens;
    private final AuthorizationService authorization;
    private final TenantDirectory tenants;
    private final TenantLocks locks;
    private final PrincipalStateCache principals;
    private final AuditService audit;

    UserAdminService(UserRepository users, InvitationRepository invitations, RefreshTokenRepository refreshTokens,
            AuthorizationService authorization, TenantDirectory tenants, TenantLocks locks, PrincipalStateCache principals,
            AuditService audit) {
        this.users = users;
        this.invitations = invitations;
        this.refreshTokens = refreshTokens;
        this.authorization = authorization;
        this.tenants = tenants;
        this.locks = locks;
        this.principals = principals;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserView> list(String status, String q, Integer page, Integer size) {
        CurrentUser.require();
        Specification<User> spec = (root, query, cb) -> cb.conjunction();
        if (status != null && !status.isBlank()) {
            UserStatus wanted = parseStatus(status);
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), wanted));
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.strip().toLowerCase(Locale.ROOT).replace("%", "\\%").replace("_", "\\_") + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("email")), like, '\\'),
                    cb.like(cb.lower(root.get("firstName")), like, '\\'),
                    cb.like(cb.lower(root.get("lastName")), like, '\\')));
        }
        var result = users.findAll(spec, Paging.of(page, size, Sort.by("createdAt", "id")));
        Map<UUID, String> names = authorization.roleNames(
                result.getContent().stream().flatMap(u -> u.getRoleIds().stream()).toList());
        return PageResponse.from(result, u -> view(u, names));
    }

    @Transactional(readOnly = true)
    public UserView get(UUID id) {
        CurrentUser.require();
        User user = find(id);
        return view(user, authorization.roleNames(user.getRoleIds()));
    }

    @Transactional
    public UserView update(UUID id, UpdateUserCommand command) {
        CurrentUser actor = CurrentUser.require();
        User user = find(id);
        boolean renaming = command.firstName() != null || command.lastName() != null;
        if (renaming) {
            requireAuthority("identity.user.update");
            Map<String, Object> before = Map.of("firstName", user.getFirstName(), "lastName", user.getLastName());
            user.rename(command.firstName() == null ? null : Names.require(command.firstName(), "firstName"),
                    command.lastName() == null ? null : Names.require(command.lastName(), "lastName"));
            audit.record(AuditEntry.of("UserUpdated", "User", id).withBefore(before)
                    .withAfter(Map.of("firstName", user.getFirstName(), "lastName", user.getLastName())));
        }
        if (command.status() != null) {
            requireAuthority("identity.user.disable");
            UserStatus target = parseStatus(command.status());
            if (target == UserStatus.DISABLED && user.getStatus() != UserStatus.DISABLED) {
                disable(actor, user);
            } else if (target == UserStatus.ACTIVE && user.getStatus() == UserStatus.DISABLED) {
                enable(user);
            } else if (target == UserStatus.INVITED) {
                throw ApiProblem.badRequestField("status", "Status must be ACTIVE or DISABLED.");
            }
        }
        users.flush();
        return view(user, authorization.roleNames(user.getRoleIds()));
    }

    @Transactional
    public UserView assignRoles(UUID id, Set<UUID> requested) {
        CurrentUser.require();
        User user = find(id);
        Set<UUID> wanted = requested == null ? Set.of() : Set.copyOf(requested);
        Set<UUID> current = user.getRoleIds();
        Set<UUID> added = difference(wanted, current);
        Set<UUID> removed = difference(current, wanted);
        if (!added.isEmpty()) {
            authorization.checkGrantable(added);
        }
        // User's @Version is part of the last-owner invariant: the decision below uses a snapshot read before the
        // owner lock, and a concurrent change to this user fails the version check at flush.
        UUID ownerRole = authorization.ownerRoleId();
        if (removed.contains(ownerRole)) {
            authorization.requireOwnerActor();
            locks.lock("owners");
            if (user.getStatus() == UserStatus.ACTIVE && users.countByRoleAndStatus(ownerRole, UserStatus.ACTIVE) <= 1) {
                throw ApiProblem.conflict(LAST_OWNER);
            }
        }
        Map<UUID, String> names = authorization.roleNames(union(current, wanted));
        user.replaceRoles(wanted);
        users.flush();
        audit.record(AuditEntry.of("UserRolesChanged", "User", id)
                .withBefore(Map.of("roles", sortedNames(current, names)))
                .withAfter(Map.of("roles", sortedNames(wanted, names))));
        evictAfterCommit(user);
        return view(user, names);
    }

    private void disable(CurrentUser actor, User user) {
        if (user.getId().equals(actor.userId())) {
            throw ApiProblem.conflict("You can't disable your own account.");
        }
        // User's @Version is part of the last-owner invariant: the decision below uses a snapshot read before the
        // owner lock, and a concurrent change to this user fails the version check at flush.
        UUID ownerRole = authorization.ownerRoleId();
        if (user.getRoleIds().contains(ownerRole)) {
            authorization.requireOwnerActor();
            locks.lock("owners");
            if (users.countByRoleAndStatus(ownerRole, UserStatus.ACTIVE) <= 1) {
                throw ApiProblem.conflict(LAST_OWNER);
            }
        }
        user.disable();
        refreshTokens.revokeAllForUser(user.getId(), RevokeReason.LOGOUT_ALL, Instant.now());
        audit.record(AuditEntry.of("UserDisabled", "User", user.getId()));
        evictAfterCommit(user);
    }

    private void enable(User user) {
        if (user.getRoleIds().contains(authorization.ownerRoleId())) {
            authorization.requireOwnerActor();
        }
        locks.lock("seats");
        Integer maxUsers = tenants.currentLimits().maxUsers();
        if (maxUsers != null
                && users.countByStatus(UserStatus.ACTIVE) + invitations.countPending(Instant.now()) >= maxUsers) {
            throw ApiProblem.conflict("Your plan allows " + maxUsers + " users. Upgrade to add more.");
        }
        user.enable();
        audit.record(AuditEntry.of("UserEnabled", "User", user.getId()));
        evictAfterCommit(user);
    }

    private void evictAfterCommit(User user) {
        UUID tenantId = user.getTenantId();
        UUID userId = user.getId();
        AfterCommit.run(() -> principals.evict(tenantId, userId));
    }

    private User find(UUID id) {
        return users.findById(id).orElseThrow(() -> ApiProblem.notFound("User not found."));
    }

    private static void requireAuthority(String code) {
        if (!CurrentAuthorities.has(code)) {
            throw ApiProblem.forbidden(FORBIDDEN);
        }
    }

    private static UserStatus parseStatus(String raw) {
        try {
            return UserStatus.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiProblem.badRequestField("status", "Status must be ACTIVE or DISABLED.");
        }
    }

    private static Set<UUID> difference(Set<UUID> a, Set<UUID> b) {
        Set<UUID> result = new HashSet<>(a);
        result.removeAll(b);
        return result;
    }

    private static Set<UUID> union(Collection<UUID> a, Collection<UUID> b) {
        Set<UUID> result = new HashSet<>(a);
        result.addAll(b);
        return result;
    }

    private static List<String> sortedNames(Collection<UUID> ids, Map<UUID, String> names) {
        return ids.stream().map(names::get).sorted().toList();
    }

    private static UserView view(User u, Map<UUID, String> roleNames) {
        List<UserView.RoleRef> roles = u.getRoleIds().stream()
                .map(r -> new UserView.RoleRef(r, roleNames.get(r)))
                .sorted(java.util.Comparator.comparing(UserView.RoleRef::name))
                .toList();
        return new UserView(u.getId(), u.getEmail(), u.getFirstName(), u.getLastName(), u.getStatus().name(),
                u.isEmailVerified(), roles, u.getLastLoginAt(), u.getCreatedAt());
    }
}
