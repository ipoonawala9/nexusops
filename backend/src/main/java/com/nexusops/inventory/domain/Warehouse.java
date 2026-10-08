package com.nexusops.inventory.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "warehouses")
public class Warehouse extends TenantOwnedEntity {

    @Column(nullable = false)
    private String code;

    @Column(name = "code_key", nullable = false)
    private String codeKey;

    @Column(nullable = false)
    private String name;

    private String address;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Warehouse() {}

    public Warehouse(UUID id, String code, String codeKey, String name, String address) {
        super(id);
        this.createdAt = Instant.now();
        apply(code, codeKey, name, address);
    }

    public void apply(String newCode, String newKey, String newName, String newAddress) {
        this.code = newCode;
        this.codeKey = newKey;
        this.name = newName;
        this.address = newAddress;
        this.updatedAt = Instant.now();
    }

    public void archive(Instant now) {
        this.archivedAt = now;
        this.updatedAt = now;
    }

    public void restore() {
        this.archivedAt = null;
        this.updatedAt = Instant.now();
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public String getCode() {
        return code;
    }

    public String getCodeKey() {
        return codeKey;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public long getVersion() {
        return version;
    }
}
