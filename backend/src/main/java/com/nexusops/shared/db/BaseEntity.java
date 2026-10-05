package com.nexusops.shared.db;

import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Entities get their UUIDv7 id at construction ({@code Ids.newId()}); {@link Persistable} tells
 * Spring Data they are new, so save() persists without a wasted SELECT.
 */
@MappedSuperclass
public abstract class BaseEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Transient
    private boolean isNew = true;

    protected BaseEntity() {}

    protected BaseEntity(UUID id) {
        this.id = id;
    }

    /** For entities built by static factories; may be called once. */
    protected void initId(UUID newId) {
        if (this.id != null) {
            throw new IllegalStateException("id already set");
        }
        this.id = newId;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
