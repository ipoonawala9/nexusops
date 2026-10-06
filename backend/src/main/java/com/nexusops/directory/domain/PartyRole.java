package com.nexusops.directory.domain;

import com.nexusops.directory.PartyRoleType;
import com.nexusops.directory.RoleStatus;
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

/** One business role of one party. Never deleted: ending a role makes it INACTIVE. */
@Entity
@Table(name = "party_roles")
public class PartyRole extends TenantOwnedEntity {

    @Column(name = "party_id", nullable = false, updatable = false)
    private UUID partyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PartyRoleType role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RoleStatus status;

    private LocalDate since;

    @Column(name = "employee_number")
    private String employeeNumber;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected PartyRole() {}

    public PartyRole(UUID id, UUID partyId, PartyRoleType role) {
        super(id);
        this.partyId = partyId;
        this.role = role;
        this.status = RoleStatus.ACTIVE;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void update(RoleStatus newStatus, LocalDate newSince, String newEmployeeNumber) {
        this.status = newStatus;
        this.since = newSince;
        this.employeeNumber = newEmployeeNumber;
        this.updatedAt = Instant.now();
    }

    public UUID getPartyId() {
        return partyId;
    }

    public PartyRoleType getRole() {
        return role;
    }

    public RoleStatus getStatus() {
        return status;
    }

    public LocalDate getSince() {
        return since;
    }

    public String getEmployeeNumber() {
        return employeeNumber;
    }
}
