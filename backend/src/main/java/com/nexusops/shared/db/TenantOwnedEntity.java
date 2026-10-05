package com.nexusops.shared.db;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** Base for every tenant-owned entity: tenant_id is stamped and filtered by Hibernate, never set by code. */
@MappedSuperclass
public abstract class TenantOwnedEntity extends BaseEntity {

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    protected TenantOwnedEntity() {}

    protected TenantOwnedEntity(UUID id) {
        super(id);
    }

    public UUID getTenantId() {
        return tenantId;
    }
}
