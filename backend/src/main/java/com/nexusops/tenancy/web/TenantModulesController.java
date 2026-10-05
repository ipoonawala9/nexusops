package com.nexusops.tenancy.web;

import com.nexusops.tenancy.ModuleState;
import com.nexusops.tenancy.TenantDirectory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenant/modules")
class TenantModulesController {

    record ToggleRequest(@NotNull Boolean enabled) {}

    private final TenantDirectory directory;

    TenantModulesController(TenantDirectory directory) {
        this.directory = directory;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('tenant.settings.read')")
    List<ModuleState> list() {
        return directory.modules();
    }

    @PutMapping("/{code}")
    @PreAuthorize("hasAuthority('tenant.modules.manage')")
    ModuleState toggle(@PathVariable String code, @Valid @RequestBody ToggleRequest request) {
        return directory.setModuleEnabled(code, request.enabled());
    }
}
