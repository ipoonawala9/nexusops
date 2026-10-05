package com.nexusops.tenancy.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "tenant_modules")
public class TenantModule extends TenantOwnedEntity {

    @Column(name = "module_code", nullable = false)
    private String moduleCode;

    @Column(nullable = false)
    private boolean enabled;

    protected TenantModule() {}

    public TenantModule(UUID id, String moduleCode, boolean enabled) {
        super(id);
        this.moduleCode = moduleCode;
        this.enabled = enabled;
    }

    public String getModuleCode() {
        return moduleCode;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
