package com.nexusops.crm.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "opportunities")
public class Opportunity extends TenantOwnedEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "contact_id")
    private UUID contactId;

    @Column(name = "stage_id", nullable = false)
    private UUID stageId;

    @Column(precision = 19, scale = 4)
    private BigDecimal amount;

    private String currency;

    @Column(name = "expected_close_on")
    private LocalDate expectedCloseOn;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "lead_id", updatable = false)
    private UUID leadId;

    private String description;

    @Column(name = "lost_reason")
    private String lostReason;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Opportunity() {}

    public Opportunity(UUID id, OpportunityDetails details, UUID stageId, UUID leadId, UUID createdBy) {
        super(id);
        this.stageId = stageId;
        this.leadId = leadId;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        apply(details);
    }

    public void apply(OpportunityDetails d) {
        this.name = d.name();
        this.accountId = d.accountId();
        this.contactId = d.contactId();
        this.amount = d.amount();
        this.currency = d.currency();
        this.expectedCloseOn = d.expectedCloseOn();
        this.ownerId = d.ownerId();
        this.description = d.description();
        this.updatedAt = Instant.now();
    }

    /** {@code closed} is null for an open stage; {@code reason} only for the Lost stage. */
    public void moveTo(UUID stage, Instant closed, String reason) {
        this.stageId = stage;
        this.closedAt = closed;
        this.lostReason = reason;
        this.updatedAt = Instant.now();
    }

    public String getName() {
        return name;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getContactId() {
        return contactId;
    }

    public UUID getStageId() {
        return stageId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getExpectedCloseOn() {
        return expectedCloseOn;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public UUID getLeadId() {
        return leadId;
    }

    public String getDescription() {
        return description;
    }

    public String getLostReason() {
        return lostReason;
    }

    public Instant getClosedAt() {
        return closedAt;
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
