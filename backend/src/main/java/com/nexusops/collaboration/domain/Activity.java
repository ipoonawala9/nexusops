package com.nexusops.collaboration.domain;

import com.nexusops.collaboration.ActivityType;
import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** One timeline entry. Immutable once logged (the database refuses UPDATE/DELETE from the app role). */
@Entity
@Immutable
@Table(name = "activities")
public class Activity extends TenantOwnedEntity {

    @Column(name = "subject_type", nullable = false)
    private String subjectType;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActivityType type;

    @Column(nullable = false)
    private String summary;

    private String body;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "author_id")
    private UUID authorId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Activity() {}

    public Activity(UUID id, String subjectType, UUID subjectId, ActivityType type, String summary, String body,
            Instant occurredAt, UUID authorId) {
        super(id);
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.type = type;
        this.summary = summary;
        this.body = body;
        this.occurredAt = occurredAt;
        this.authorId = authorId;
        this.createdAt = Instant.now();
    }

    public String getSubjectType() {
        return subjectType;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public ActivityType getType() {
        return type;
    }

    public String getSummary() {
        return summary;
    }

    public String getBody() {
        return body;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public UUID getAuthorId() {
        return authorId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
