package com.nexusops.platform.web;

import com.nexusops.platform.application.PlatformTenantAdmin;
import com.nexusops.platform.application.PlatformTenantQueries;
import com.nexusops.platform.application.PlatformTenantView;
import com.nexusops.shared.web.PageResponse;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform/tenants")
class PlatformTenantController {

    /** The reason is validated by TenantDirectory (1–500 characters, field error "reason"). */
    record StatusChangeRequest(String reason) {}

    private final PlatformTenantQueries queries;
    private final PlatformTenantAdmin admin;

    PlatformTenantController(PlatformTenantQueries queries, PlatformTenantAdmin admin) {
        this.queries = queries;
        this.admin = admin;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('platform.tenant.read')")
    PageResponse<PlatformTenantView> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return queries.list(status, q, page, size);
    }

    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasAuthority('platform.tenant.suspend')")
    PlatformTenantView suspend(@PathVariable UUID id, @RequestBody StatusChangeRequest request) {
        return admin.suspend(id, request.reason());
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasAuthority('platform.tenant.suspend')")
    PlatformTenantView reactivate(@PathVariable UUID id, @RequestBody StatusChangeRequest request) {
        return admin.reactivate(id, request.reason());
    }
}
