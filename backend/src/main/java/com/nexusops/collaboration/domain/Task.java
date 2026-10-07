package com.nexusops.collaboration.domain;

import com.nexusops.collaboration.TaskPriority;
import com.nexusops.collaboration.TaskStatus;
import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "tasks")
public class Task extends TenantOwnedEntity {

    @Column(nullable = false)
    private String title;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskPriority priority;

    @Column(name = "due_on")
    private LocalDate dueOn;

    @Column(name = "assignee_id")
    private UUID assigneeId;

    @Column(name = "subject_type")
    private String subjectType;

    @Column(name = "subject_id")
    private UUID subjectId;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Task() {}

    public Task(UUID id, TaskDetails details, UUID createdBy) {
        super(id);
        this.status = TaskStatus.OPEN;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        apply(details);
    }

    public void apply(TaskDetails details) {
        this.title = details.title();
        this.description = details.description();
        this.priority = details.priority();
        this.dueOn = details.dueOn();
        this.assigneeId = details.assigneeId();
        this.subjectType = details.subjectType();
        this.subjectId = details.subjectId();
        this.updatedAt = Instant.now();
    }

    /** DONE stamps completedAt; any other status clears it. */
    public void changeStatus(TaskStatus newStatus, Instant now) {
        this.status = newStatus;
        this.completedAt = newStatus == TaskStatus.DONE ? now : null;
        this.updatedAt = now;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public TaskPriority getPriority() {
        return priority;
    }

    public LocalDate getDueOn() {
        return dueOn;
    }

    public UUID getAssigneeId() {
        return assigneeId;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
