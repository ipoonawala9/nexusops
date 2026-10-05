package com.nexusops.authorization;

import com.nexusops.authorization.domain.Permission;
import com.nexusops.authorization.domain.PermissionRepository;
import com.nexusops.authorization.domain.Role;
import com.nexusops.authorization.domain.RoleRepository;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Permissions, not role names, decide authorization (ADR-0004). */
@Service
public class AuthorizationService {

    private final RoleRepository roles;
    private final PermissionRepository permissions;

    AuthorizationService(RoleRepository roles, PermissionRepository permissions) {
        this.roles = roles;
        this.permissions = permissions;
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
}
