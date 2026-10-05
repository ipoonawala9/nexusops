package com.nexusops.identity.web;

import com.nexusops.identity.application.UpdateUserCommand;
import com.nexusops.identity.application.UserAdminService;
import com.nexusops.identity.application.UserView;
import com.nexusops.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
class UserController {

    record UpdateUserRequest(@Size(max = 80) String firstName, @Size(max = 80) String lastName,
            @Size(max = 20) String status) {}

    record AssignRolesRequest(@NotNull Set<@NotNull UUID> roleIds) {}

    private final UserAdminService users;

    UserController(UserAdminService users) {
        this.users = users;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('identity.user.read')")
    PageResponse<UserView> list(@RequestParam(required = false) String status, @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return users.list(status, q, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('identity.user.read')")
    UserView get(@PathVariable UUID id) {
        return users.get(id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('identity.user.update', 'identity.user.disable')")
    UserView update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return users.update(id, new UpdateUserCommand(request.firstName(), request.lastName(), request.status()));
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('authorization.role.assign')")
    UserView assignRoles(@PathVariable UUID id, @Valid @RequestBody AssignRolesRequest request) {
        return users.assignRoles(id, request.roleIds());
    }
}
