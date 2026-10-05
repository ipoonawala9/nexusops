package com.nexusops.authorization;

import com.nexusops.authorization.domain.Permission;
import com.nexusops.authorization.domain.PermissionRepository;
import com.nexusops.authorization.domain.Role;
import com.nexusops.authorization.domain.RoleRepository;
import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.security.CurrentActor;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.tenancy.TenantDirectory;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Permissions, not role names, decide authorization (ADR-0004). */
@Service
public class AuthorizationService {

    private final RoleRepository roles;
    private final PermissionRepository permissions;

    private final TenantDirectory tenants;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    AuthorizationService(RoleRepository roles, PermissionRepository permissions, TenantDirectory tenants,
            AuditService audit, ApplicationEventPublisher events) {
        this.roles = roles;
        this.permissions = permissions;
        this.tenants = tenants;
        this.audit = audit;
        this.events = events;
    }

    /** Creates TENANT_OWNER and TENANT_ADMIN (both with the full catalog) in the current tenant. */
    @Transactional
    public UUID createSystemRoles() {
        TenantContext.requireTenantId();
        Set<String> all = permissions.findAll().stream().map(Permission::getCode).collect(Collectors.toSet());
        Role owner = new Role(Ids.newId(), SystemRoles.OWNER, "Workspace owner — full access", true, all);
        Role admin = new Role(Ids.newId(), SystemRoles.ADMIN, "Workspace administrator — full access", true, all);
        roles.saveAll(List.of(owner, admin));
        return owner.getId();
    }

    /** Union of the roles' permissions, minus permissions of modules not enabled for the tenant. */
    @Transactional(readOnly = true)
    public Set<String> effectivePermissions(Collection<UUID> roleIds, Collection<String> enabledModules) {
        TenantContext.requireTenantId();
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        Map<String, String> moduleOf = permissions.findAll().stream()
                .collect(HashMap::new, (m, p) -> m.put(p.getCode(), p.getModuleCode()), Map::putAll);
        Set<String> result = new HashSet<>();
        for (Role role : roles.findAllById(roleIds)) {
            for (String code : role.getPermissions()) {
                String module = moduleOf.get(code);
                if (module == null || enabledModules.contains(module)) {
                    result.add(code);
                }
            }
        }
        return Set.copyOf(result);
    }

    static final String ESCALATION = "You can't grant permissions you don't have.";
    static final String OWNER_ONLY = "Only workspace owners can manage the owner role.";
    static final String SYSTEM_IMMUTABLE = "System roles can't be changed.";
    static final String HIERARCHY = "You can't change a role with permissions you don't have.";
    static final String USER_HIERARCHY = "You can't manage a user with permissions you don't have.";

    /** Union of the roles' permissions WITHOUT module gating: what this actor may grant to others. */
    @Transactional(readOnly = true)
    public Set<String> grantablePermissions(Collection<UUID> roleIds) {
        TenantContext.requireTenantId();
        Set<String> result = new HashSet<>();
        roles.findAllById(roleIds).forEach(r -> result.addAll(r.getPermissions()));
        return Set.copyOf(result);
    }

    @Transactional(readOnly = true)
    public List<PermissionView> permissionCatalog() {
        List<String> enabled = tenants.enabledModules();
        return permissions.findAll().stream()
                .sorted(Comparator.comparing(Permission::getCode))
                .map(p -> new PermissionView(p.getCode(), p.getModuleCode(), p.getDescription(),
                        p.getModuleCode() == null || enabled.contains(p.getModuleCode())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RoleView> listRoles() {
        TenantContext.requireTenantId();
        return roles.findAllByOrderBySystemDescNameAsc().stream().map(AuthorizationService::view).toList();
    }

    @Transactional(readOnly = true)
    public RoleView getRole(UUID id) {
        return view(find(id));
    }

    @Transactional
    public RoleView createRole(CreateRoleCommand command) {
        TenantContext.requireTenantId();
        String name = validName(command.name());
        String description = validDescription(command.description());
        Set<String> codes = validPermissions(command.permissions());
        requireGrantable(codes);
        if (isReservedName(name) || roles.existsByNameIgnoreCase(name)) {
            throw nameTaken();
        }
        Role role = new Role(Ids.newId(), name, description, false, codes);
        try {
            roles.saveAndFlush(role);
        } catch (DataIntegrityViolationException race) {
            throw nameTaken();
        }
        audit.record(AuditEntry.of("RoleCreated", "Role", role.getId())
                .withAfter(Map.of("name", name, "permissions", sorted(codes))));
        return view(role);
    }

    @Transactional
    public RoleView updateRole(UUID id, String rawName, String rawDescription) {
        Role role = mutable(id);
        RoleView before = view(role);
        String name = rawName == null ? null : validName(rawName);
        if (name != null && !name.equalsIgnoreCase(role.getName())
                && (isReservedName(name) || roles.existsByNameIgnoreCase(name))) {
            throw nameTaken();
        }
        role.rename(name, rawDescription == null ? null : validDescription(rawDescription));
        try {
            roles.flush();
        } catch (DataIntegrityViolationException race) {
            throw nameTaken();
        }
        audit.record(AuditEntry.of("RoleUpdated", "Role", id)
                .withBefore(Map.of("name", before.name(), "description", String.valueOf(before.description())))
                .withAfter(Map.of("name", role.getName(), "description", String.valueOf(role.getDescription()))));
        return view(role);
    }

    @Transactional
    public RoleView replacePermissions(UUID id, Set<String> requested) {
        Role role = mutable(id);
        Set<String> codes = validPermissions(requested);
        requireGrantable(codes);
        List<String> before = view(role).permissions();
        role.replacePermissions(codes);
        roles.flush();
        audit.record(AuditEntry.of("RolePermissionsChanged", "Role", id)
                .withBefore(Map.of("permissions", before))
                .withAfter(Map.of("permissions", sorted(codes))));
        events.publishEvent(new RolesChanged(TenantContext.requireTenantId()));
        return view(role);
    }

    @Transactional
    public void deleteRole(UUID id) {
        Role role = mutable(id);
        long assigned = roles.countAssignments(id);
        if (assigned > 0) {
            throw ApiProblem.conflict("Remove this role from " + assigned + " user(s) first.");
        }
        roles.delete(role);
        audit.record(AuditEntry.of("RoleDeleted", "Role", id).withBefore(Map.of("name", role.getName())));
        events.publishEvent(new RolesChanged(TenantContext.requireTenantId()));
    }

    @Transactional(readOnly = true)
    public UUID ownerRoleId() {
        TenantContext.requireTenantId();
        return roles.findByNameAndSystemTrue(SystemRoles.OWNER)
                .orElseThrow(() -> new IllegalStateException("Tenant has no owner role")).getId();
    }

    @Transactional(readOnly = true)
    public Map<UUID, String> roleNames(Collection<UUID> roleIds) {
        TenantContext.requireTenantId();
        Map<UUID, String> names = new HashMap<>();
        roles.findAllById(roleIds).forEach(r -> names.put(r.getId(), r.getName()));
        return names;
    }

    /** May the current actor grant every one of these roles? (Unknown → 400, owner role → owners only, superset → 403.) */
    @Transactional(readOnly = true)
    public void checkGrantable(Collection<UUID> roleIds) {
        TenantContext.requireTenantId();
        List<Role> found = roles.findAllById(roleIds);
        if (found.size() != new HashSet<>(roleIds).size()) {
            throw ApiProblem.badRequestField("roleIds", "Unknown role.");
        }
        Set<String> requested = new HashSet<>();
        for (Role role : found) {
            if (role.isSystem() && SystemRoles.OWNER.equals(role.getName())) {
                requireOwnerActor();
            }
            requested.addAll(role.getPermissions());
        }
        requireGrantable(requested);
    }

    /** 403 unless every permission the target user's roles carry is within the actor's grantable set. */
    @Transactional(readOnly = true)
    public void requireOutranks(Collection<UUID> targetRoleIds) {
        if (!CurrentActor.require().grantablePermissions().containsAll(grantablePermissions(targetRoleIds))) {
            throw ApiProblem.forbidden(USER_HIERARCHY);
        }
    }

    @Transactional(readOnly = true)
    public void requireOwnerActor() {
        if (!CurrentActor.require().roleIds().contains(ownerRoleId())) {
            throw ApiProblem.forbidden(OWNER_ONLY);
        }
    }

    private void requireGrantable(Set<String> codes) {
        if (!CurrentActor.require().grantablePermissions().containsAll(codes)) {
            throw ApiProblem.forbidden(ESCALATION);
        }
    }

    private Role find(UUID id) {
        TenantContext.requireTenantId();
        return roles.findById(id).orElseThrow(() -> ApiProblem.notFound("Role not found."));
    }

    /**
     * A custom role the current actor may change: 404 if not in this tenant, 409 if a system role, and 403 unless the
     * role's current permissions are all within the actor's grantable set (no weakening or deleting a stronger role).
     * Reads the managed entity's permissions; {@code Role @Version} turns a concurrent change into a 409 at flush.
     */
    private Role mutable(UUID id) {
        Role role = find(id);
        if (role.isSystem()) {
            throw ApiProblem.conflict(SYSTEM_IMMUTABLE);
        }
        if (!CurrentActor.require().grantablePermissions().containsAll(role.getPermissions())) {
            throw ApiProblem.forbidden(HIERARCHY);
        }
        return role;
    }

    private Set<String> validPermissions(Set<String> requested) {
        Set<String> codes = requested == null ? Set.of() : Set.copyOf(requested);
        Set<String> known = permissions.findAll().stream().map(Permission::getCode).collect(Collectors.toSet());
        for (String code : codes) {
            if (!known.contains(code)) {
                throw ApiProblem.badRequestField("permissions", "Unknown permission: " + code);
            }
        }
        return codes;
    }

    private static String validName(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > 60) {
            throw ApiProblem.badRequestField("name", "Enter a name between 1 and 60 characters.");
        }
        return name;
    }

    private static String validDescription(String raw) {
        if (raw == null) return null;
        String description = raw.strip();
        if (description.length() > 255) {
            throw ApiProblem.badRequestField("description", "Use at most 255 characters.");
        }
        return description;
    }

    private static boolean isReservedName(String name) {
        return name.equalsIgnoreCase(SystemRoles.OWNER) || name.equalsIgnoreCase(SystemRoles.ADMIN);
    }

    private static ApiProblem nameTaken() {
        return ApiProblem.conflictField("name", "A role with this name already exists.");
    }

    private static List<String> sorted(Collection<String> codes) {
        return codes.stream().sorted().toList();
    }

    private static RoleView view(Role role) {
        return new RoleView(role.getId(), role.getName(), role.getDescription(), role.isSystem(), sorted(role.getPermissions()));
    }
}
