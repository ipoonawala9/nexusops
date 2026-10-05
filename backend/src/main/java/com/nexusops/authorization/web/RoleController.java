package com.nexusops.authorization.web;

import com.nexusops.authorization.AuthorizationService;
import com.nexusops.authorization.CreateRoleCommand;
import com.nexusops.authorization.RoleView;
import com.nexusops.authorization.web.RoleDtos.CreateRoleRequest;
import com.nexusops.authorization.web.RoleDtos.PermissionsRequest;
import com.nexusops.authorization.web.RoleDtos.UpdateRoleRequest;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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

    /** ADR-0004: escalation guard (403) and the hierarchy rule — the target role's permissions must be grantable. */
    static final String FORBIDDEN_DOC = "Missing permission, the role holds permissions the caller can't grant, or the "
            + "requested permissions exceed the caller's.";

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
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = RoleView.class)))
    @ApiResponse(responseCode = "403", description = FORBIDDEN_DOC,
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PreAuthorize("hasAuthority('authorization.role.manage')")
    RoleView update(@PathVariable UUID id, @Valid @RequestBody UpdateRoleRequest request) {
        return authorization.updateRole(id, request.name(), request.description());
    }

    @PutMapping("/{id}/permissions")
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = RoleView.class)))
    @ApiResponse(responseCode = "403", description = FORBIDDEN_DOC,
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PreAuthorize("hasAuthority('authorization.role.manage')")
    RoleView replacePermissions(@PathVariable UUID id, @Valid @RequestBody PermissionsRequest request) {
        return authorization.replacePermissions(id, request.permissions());
    }

    @DeleteMapping("/{id}")
    @ApiResponse(responseCode = "204", description = "No Content")
    @ApiResponse(responseCode = "403", description = FORBIDDEN_DOC,
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('authorization.role.manage')")
    void delete(@PathVariable UUID id) {
        authorization.deleteRole(id);
    }
}
