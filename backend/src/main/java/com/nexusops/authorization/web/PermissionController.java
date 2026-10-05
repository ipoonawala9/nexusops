package com.nexusops.authorization.web;

import com.nexusops.authorization.AuthorizationService;
import com.nexusops.authorization.PermissionView;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PermissionController {

    private final AuthorizationService authorization;

    PermissionController(AuthorizationService authorization) {
        this.authorization = authorization;
    }

    @GetMapping("/api/v1/permissions")
    @PreAuthorize("hasAuthority('authorization.role.read')")
    List<PermissionView> catalog() {
        return authorization.permissionCatalog();
    }
}
