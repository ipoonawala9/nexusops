package com.nexusops.tenancy.web;

import com.nexusops.tenancy.TenantDirectory;
import com.nexusops.tenancy.TenantSettings;
import com.nexusops.tenancy.UpdateTenantSettings;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenant")
class TenantController {

    private final TenantDirectory directory;

    TenantController(TenantDirectory directory) {
        this.directory = directory;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('tenant.settings.read')")
    TenantSettings get() {
        return directory.currentSettings();
    }

    @PatchMapping
    @PreAuthorize("hasAuthority('tenant.settings.update')")
    TenantSettings update(@Valid @RequestBody UpdateTenantSettingsRequest request) {
        return directory.updateSettings(new UpdateTenantSettings(
                request.name(), request.timezone(), request.locale(), request.currency()));
    }
}
