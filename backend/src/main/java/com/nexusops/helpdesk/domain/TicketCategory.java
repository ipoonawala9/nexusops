package com.nexusops.helpdesk.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ticket_categories")
public class TicketCategory extends TenantOwnedEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "name_key", nullable = false)
    private String nameKey;

    private String description;

    @Column(name = "default_assignee_id")
    private UUID defaultAssigneeId;

    @Column(nullable = false)
    private int position;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected TicketCategory() {}

    public TicketCategory(UUID id, String name, String nameKey, String description, UUID defaultAssigneeId,
            int position) {
        super(id);
        this.position = position;
        this.createdAt = Instant.now();
        apply(name, nameKey, description, defaultAssigneeId);
    }

    public void apply(String newName, String newKey, String newDescription, UUID newDefaultAssignee) {
        this.name = newName;
        this.nameKey = newKey;
        this.description = newDescription;
        this.defaultAssigneeId = newDefaultAssignee;
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

    public String getName() {
        return name;
    }

    public String getNameKey() {
        return nameKey;
    }

    public String getDescription() {
        return description;
    }

    public UUID getDefaultAssigneeId() {
        return defaultAssigneeId;
    }

    public int getPosition() {
        return position;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public long getVersion() {
        return version;
    }
}
