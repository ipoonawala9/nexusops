package com.nexusops.crm.domain;

import com.nexusops.crm.StageKind;
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
@Table(name = "pipeline_stages")
public class PipelineStage extends TenantOwnedEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "name_key", nullable = false)
    private String nameKey;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private int probability;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private StageKind kind;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected PipelineStage() {}

    public PipelineStage(UUID id, String name, String nameKey, int position, int probability, StageKind kind) {
        super(id);
        this.name = name;
        this.nameKey = nameKey;
        this.position = position;
        this.probability = probability;
        this.kind = kind;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void rename(String newName, String newKey, int newProbability) {
        this.name = newName;
        this.nameKey = newKey;
        this.probability = newProbability;
        this.updatedAt = Instant.now();
    }

    public void moveTo(int newPosition) {
        if (this.position != newPosition) {
            this.position = newPosition;
            this.updatedAt = Instant.now();
        }
    }

    public String getName() {
        return name;
    }

    public String getNameKey() {
        return nameKey;
    }

    public int getPosition() {
        return position;
    }

    public int getProbability() {
        return probability;
    }

    public StageKind getKind() {
        return kind;
    }

    public long getVersion() {
        return version;
    }
}
