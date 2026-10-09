package com.nexusops.helpdesk.domain;

import com.nexusops.helpdesk.Channel;
import com.nexusops.helpdesk.Priority;
import com.nexusops.helpdesk.TicketStatus;
import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * A support ticket. SLA due times are stored; the service recomputes them through SlaClock whenever the priority or
 * the pause state changes (spec §2). The generated {@code search} column is not mapped.
 */
@Entity
@Table(name = "tickets")
public class Ticket extends TenantOwnedEntity {

    @Column(nullable = false, updatable = false)
    private String number;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String description;

    @Column(name = "requester_id", nullable = false)
    private UUID requesterId;

    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "linked_type")
    private String linkedType;

    @Column(name = "linked_id")
    private UUID linkedId;

    @Column(name = "category_id")
    private UUID categoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    @Column(name = "assignee_id")
    private UUID assigneeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketStatus status;

    @Column(name = "first_response_due_at", nullable = false)
    private Instant firstResponseDueAt;

    @Column(name = "resolution_due_at", nullable = false)
    private Instant resolutionDueAt;

    @Column(name = "resolution_clock_started_at", nullable = false)
    private Instant resolutionClockStartedAt;

    @Column(name = "paused_seconds", nullable = false)
    private long pausedSeconds;

    @Column(name = "paused_at")
    private Instant pausedAt;

    @Column(name = "first_responded_at")
    private Instant firstRespondedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "resolution_note")
    private String resolutionNote;

    @Column(name = "reopen_count", nullable = false)
    private int reopenCount;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Ticket() {}

    /** A NEW ticket (OPEN when it starts with an assignee); the caller supplies the due times from SlaClock. */
    public Ticket(UUID id, String number, Priority priority, UUID assigneeId, UUID createdBy, Instant now,
            Instant firstResponseDueAt, Instant resolutionDueAt) {
        super(id);
        this.number = number;
        this.priority = priority;
        this.assigneeId = assigneeId;
        this.status = assigneeId == null ? TicketStatus.NEW : TicketStatus.OPEN;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
        this.resolutionClockStartedAt = now;
        this.firstResponseDueAt = firstResponseDueAt;
        this.resolutionDueAt = resolutionDueAt;
    }

    public void applyDetails(String newSubject, String newDescription, UUID newRequester, UUID newProduct,
            String newLinkedType, UUID newLinkedId, UUID newCategory, Channel newChannel) {
        this.subject = newSubject;
        this.description = newDescription;
        this.requesterId = newRequester;
        this.productId = newProduct;
        this.linkedType = newLinkedType;
        this.linkedId = newLinkedId;
        this.categoryId = newCategory;
        this.channel = newChannel;
        this.updatedAt = Instant.now();
    }

    /** A new priority and its due times (computed by the caller from the ticket's own clock values). */
    public void reprioritize(Priority newPriority, Instant newFirstResponseDue, Instant newResolutionDue) {
        this.priority = newPriority;
        this.firstResponseDueAt = newFirstResponseDue;
        this.resolutionDueAt = newResolutionDue;
        this.updatedAt = Instant.now();
    }

    /** Assigning someone to a NEW ticket opens it (D6). */
    public void assign(UUID newAssignee, Instant now) {
        this.assigneeId = newAssignee;
        if (newAssignee != null && status == TicketStatus.NEW) {
            this.status = TicketStatus.OPEN;
        }
        this.updatedAt = now;
    }

    /** Leaves PENDING: the waiting time no longer counts against the resolution target. */
    public void resume(long newPausedSeconds, Instant newResolutionDue, Instant now) {
        this.pausedSeconds = newPausedSeconds;
        this.resolutionDueAt = newResolutionDue;
        this.pausedAt = null;
        this.updatedAt = now;
    }

    public void pause(Instant now) {
        this.pausedAt = now;
        this.status = TicketStatus.PENDING;
        this.updatedAt = now;
    }

    public void open(Instant now) {
        this.status = TicketStatus.OPEN;
        this.updatedAt = now;
    }

    public void resolve(String note, Instant now) {
        this.status = TicketStatus.RESOLVED;
        this.resolvedAt = now;
        this.resolutionNote = note;
        this.updatedAt = now;
    }

    public void close(Instant now) {
        this.status = TicketStatus.CLOSED;
        this.closedAt = now;
        this.updatedAt = now;
    }

    /** RESOLVED → OPEN: the resolution clock restarts with the full target (D8). */
    public void reopen(Instant newResolutionDue, Instant now) {
        this.status = TicketStatus.OPEN;
        this.reopenCount++;
        this.resolvedAt = null;
        this.resolutionNote = null;
        this.resolutionClockStartedAt = now;
        this.pausedSeconds = 0;
        this.resolutionDueAt = newResolutionDue;
        this.updatedAt = now;
    }

    /** The first public reply (D9); opens a NEW ticket. Later replies change nothing here. */
    public void recordFirstResponse(Instant now) {
        if (firstRespondedAt == null) {
            this.firstRespondedAt = now;
        }
        if (status == TicketStatus.NEW) {
            this.status = TicketStatus.OPEN;
        }
        this.updatedAt = now;
    }

    /** Bumps the version for changes that live in other tables (messages). */
    public void touch(Instant now) {
        this.updatedAt = now;
    }

    public String getNumber() {
        return number;
    }

    public String getSubject() {
        return subject;
    }

    public String getDescription() {
        return description;
    }

    public UUID getRequesterId() {
        return requesterId;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getLinkedType() {
        return linkedType;
    }

    public UUID getLinkedId() {
        return linkedId;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public Priority getPriority() {
        return priority;
    }

    public Channel getChannel() {
        return channel;
    }

    public UUID getAssigneeId() {
        return assigneeId;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public Instant getFirstResponseDueAt() {
        return firstResponseDueAt;
    }

    public Instant getResolutionDueAt() {
        return resolutionDueAt;
    }

    public Instant getResolutionClockStartedAt() {
        return resolutionClockStartedAt;
    }

    public long getPausedSeconds() {
        return pausedSeconds;
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    public Instant getFirstRespondedAt() {
        return firstRespondedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public int getReopenCount() {
        return reopenCount;
    }

    public UUID getCreatedBy() {
        return createdBy;
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
