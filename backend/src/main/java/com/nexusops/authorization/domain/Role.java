package com.nexusops.authorization.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "roles")
public class Role extends TenantOwnedEntity {

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false)
    private boolean system;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "role_permissions", joinColumns = @JoinColumn(name = "role_id"))
    @Column(name = "permission_code")
    private Set<String> permissions = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Role() {}

    public Role(UUID id, String name, String description, boolean system, Set<String> permissions) {
        super(id);
        this.name = name;
        this.description = description;
        this.system = system;
        this.permissions = new HashSet<>(permissions);
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String getName() {
        return name;
    }

    public Set<String> getPermissions() {
        return Set.copyOf(permissions);
    }
}
