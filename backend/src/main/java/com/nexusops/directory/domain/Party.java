package com.nexusops.directory.domain;

import com.nexusops.directory.PartyKind;
import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** A person or an organization: the one canonical identity every module references (ADR-0008). */
@Entity
@Table(name = "parties")
public class Party extends TenantOwnedEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PartyKind kind;

    @Column(nullable = false)
    private String name;

    @Column(name = "name_key", nullable = false)
    private String nameKey;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "job_title")
    private String jobTitle;

    @Column(name = "organization_id")
    private UUID organizationId;

    private String email;

    private String phone;

    private String domain;

    private String website;

    @Column(name = "duplicate_reason")
    private String duplicateReason;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Party() {}

    private Party(UUID id, PartyKind kind) {
        super(id);
        this.kind = kind;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static Party person(UUID id, PersonDetails details) {
        Party party = new Party(id, PartyKind.PERSON);
        party.applyPerson(details);
        return party;
    }

    public static Party organization(UUID id, OrganizationDetails details) {
        Party party = new Party(id, PartyKind.ORGANIZATION);
        party.applyOrganization(details);
        return party;
    }

    public void applyPerson(PersonDetails details) {
        requireKind(PartyKind.PERSON);
        this.firstName = details.firstName();
        this.lastName = details.lastName();
        this.name = PartyNames.fullName(details.firstName(), details.lastName());
        this.nameKey = PartyNames.personKey(details.firstName(), details.lastName());
        this.jobTitle = details.jobTitle();
        this.organizationId = details.organizationId();
        this.email = details.email();
        this.phone = details.phone();
        this.updatedAt = Instant.now();
    }

    public void applyOrganization(OrganizationDetails details) {
        requireKind(PartyKind.ORGANIZATION);
        this.name = details.name();
        this.nameKey = PartyNames.organizationKey(details.name());
        this.domain = details.domain();
        this.website = details.website();
        this.email = details.email();
        this.phone = details.phone();
        this.updatedAt = Instant.now();
    }

    /** Why this record was kept although it matched existing ones (D4). */
    public void recordDuplicateReason(String reason) {
        this.duplicateReason = reason;
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

    private void requireKind(PartyKind expected) {
        if (kind != expected) {
            throw new IllegalStateException("Party " + getId() + " is a " + kind + ", not a " + expected);
        }
    }

    public PartyKind getKind() {
        return kind;
    }

    public String getName() {
        return name;
    }

    public String getNameKey() {
        return nameKey;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getJobTitle() {
        return jobTitle;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getDomain() {
        return domain;
    }

    public String getWebsite() {
        return website;
    }

    public String getDuplicateReason() {
        return duplicateReason;
    }

    public Instant getArchivedAt() {
        return archivedAt;
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
