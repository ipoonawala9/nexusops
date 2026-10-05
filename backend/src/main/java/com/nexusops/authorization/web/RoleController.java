package com.nexusops.authorization.web;

import com.nexusops.authorization.AuthorizationService;
import com.nexusops.authorization.CreateRoleCommand;
import com.nexusops.authorization.RoleView;
import com.nexusops.authorization.web.RoleDtos.CreateRoleRequest;
import com.nexusops.authorization.web.RoleDtos.PermissionsRequest;
import com.nexusops.authorization.web.RoleDtos.UpdateRoleRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/roles")
class RoleController {

    private final AuthorizationService authorization;

    RoleController(AuthorizationService authorization) {
        this.authorization = authorization;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('authorization.role.read')")
    List<RoleView> list() {
        return authorization.listRoles();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('authorization.role.manage')")
    RoleView create(@Valid @RequestBody CreateRoleRequest request) {
        return authorization.createRole(new CreateRoleCommand(request.name(), request.description(), request.permissions()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('authorization.role.read')")
    RoleView get(@PathVariable UUID id) {
        return authorization.getRole(id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('authorization.role.manage')")
    RoleView update(@PathVariable UUID id, @Valid @RequestBody UpdateRoleRequest request) {
        return authorization.updateRole(id, request.name(), request.description());
    }

    @PutMapping("/{id}/permissions")
    @PreAuthorize("hasAuthority('authorization.role.manage')")
    RoleView replacePermissions(@PathVariable UUID id, @Valid @RequestBody PermissionsRequest request) {
        return authorization.replacePermissions(id, request.permissions());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('authorization.role.manage')")
    void delete(@PathVariable UUID id) {
        authorization.deleteRole(id);
    }
}
