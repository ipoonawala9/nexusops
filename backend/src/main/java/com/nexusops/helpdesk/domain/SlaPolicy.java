package com.nexusops.helpdesk.domain;

import com.nexusops.helpdesk.Priority;
import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sla_policies")
public class SlaPolicy extends TenantOwnedEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Priority priority;

    @Column(name = "first_response_minutes", nullable = false)
    private int firstResponseMinutes;

    @Column(name = "resolution_minutes", nullable = false)
    private int resolutionMinutes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected SlaPolicy() {}

    public SlaPolicy(UUID id, Priority priority, int firstResponseMinutes, int resolutionMinutes) {
        super(id);
        this.priority = priority;
        apply(firstResponseMinutes, resolutionMinutes);
    }

    public void apply(int firstResponse, int resolution) {
        this.firstResponseMinutes = firstResponse;
        this.resolutionMinutes = resolution;
        this.updatedAt = Instant.now();
    }

    public Priority getPriority() {
        return priority;
    }

    public int getFirstResponseMinutes() {
        return firstResponseMinutes;
    }

    public int getResolutionMinutes() {
        return resolutionMinutes;
    }

    public long getVersion() {
        return version;
    }
}
