package com.nexusops.crm.domain;

import com.nexusops.crm.LeadSource;
import com.nexusops.crm.LeadStatus;
import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "leads")
public class Lead extends TenantOwnedEntity {

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "company_name")
    private String companyName;

    @Column(name = "job_title")
    private String jobTitle;

    private String email;

    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LeadSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LeadStatus status;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "estimated_value", precision = 19, scale = 4)
    private BigDecimal estimatedValue;

    private String currency;

    private String description;

    @Column(name = "disqualify_reason")
    private String disqualifyReason;

    @Column(name = "disqualified_at")
    private Instant disqualifiedAt;

    @Column(name = "converted_at")
    private Instant convertedAt;

    @Column(name = "converted_person_id")
    private UUID convertedPersonId;

    @Column(name = "converted_organization_id")
    private UUID convertedOrganizationId;

    @Column(name = "converted_opportunity_id")
    private UUID convertedOpportunityId;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Lead() {}

    public Lead(UUID id, LeadDetails details, UUID createdBy) {
        super(id);
        this.status = LeadStatus.NEW;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        apply(details);
    }

    /** Full name, else the company: a lead needs one of the two. */
    public static String displayName(String first, String last, String company) {
        String person = ((first == null ? "" : first) + " " + (last == null ? "" : last)).strip();
        return person.isEmpty() ? company : person;
    }

    public void apply(LeadDetails d) {
        this.firstName = d.firstName();
        this.lastName = d.lastName();
        this.companyName = d.companyName();
        this.jobTitle = d.jobTitle();
        this.email = d.email();
        this.phone = d.phone();
        this.source = d.source();
        this.ownerId = d.ownerId();
        this.estimatedValue = d.estimatedValue();
        this.currency = d.currency();
        this.description = d.description();
        this.updatedAt = Instant.now();
    }

    /** Callers check the transition (LeadStatus.check) first. */
    public void changeStatus(LeadStatus next, String reason, Instant at) {
        this.status = next;
        this.disqualifyReason = next == LeadStatus.DISQUALIFIED ? reason : null;
        this.disqualifiedAt = next == LeadStatus.DISQUALIFIED ? at : null;
        this.updatedAt = at;
    }

    public void markConverted(UUID personId, UUID organizationId, UUID opportunityId, Instant at) {
        this.status = LeadStatus.CONVERTED;
        this.convertedPersonId = personId;
        this.convertedOrganizationId = organizationId;
        this.convertedOpportunityId = opportunityId;
        this.convertedAt = at;
        this.disqualifyReason = null;
        this.disqualifiedAt = null;
        this.updatedAt = at;
    }

    public String getName() {
        return displayName(firstName, lastName, companyName);
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getCompanyName() {
        return companyName;
    }

    public String getJobTitle() {
        return jobTitle;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public LeadSource getSource() {
        return source;
    }

    public LeadStatus getStatus() {
        return status;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public BigDecimal getEstimatedValue() {
        return estimatedValue;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDescription() {
        return description;
    }

    public String getDisqualifyReason() {
        return disqualifyReason;
    }

    public Instant getDisqualifiedAt() {
        return disqualifiedAt;
    }

    public Instant getConvertedAt() {
        return convertedAt;
    }

    public UUID getConvertedPersonId() {
        return convertedPersonId;
    }

    public UUID getConvertedOrganizationId() {
        return convertedOrganizationId;
    }

    public UUID getConvertedOpportunityId() {
        return convertedOpportunityId;
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
