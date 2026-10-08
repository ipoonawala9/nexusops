package com.nexusops.inventory.domain;

import com.nexusops.inventory.SalesOrderStatus;
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
@Table(name = "sales_orders")
public class SalesOrder extends TenantOwnedEntity {

    @Column(nullable = false, updatable = false)
    private String number;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SalesOrderStatus status;

    @Column(nullable = false)
    private String currency;

    private String notes;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected SalesOrder() {}

    public SalesOrder(UUID id, String number, UUID customerId, UUID warehouseId, String currency, String notes,
            UUID createdBy) {
        super(id);
        this.number = number;
        this.status = SalesOrderStatus.DRAFT;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        apply(customerId, warehouseId, currency, notes);
    }

    /** Always moves updatedAt, so a lines-only edit still raises the version. */
    public void apply(UUID newCustomer, UUID newWarehouse, String newCurrency, String newNotes) {
        this.customerId = newCustomer;
        this.warehouseId = newWarehouse;
        this.currency = newCurrency;
        this.notes = newNotes;
        this.updatedAt = Instant.now();
    }

    public void confirm(Instant now) {
        this.status = SalesOrderStatus.CONFIRMED;
        this.confirmedAt = now;
        this.updatedAt = now;
    }

    public void fulfil(Instant now) {
        this.status = SalesOrderStatus.FULFILLED;
        this.fulfilledAt = now;
        this.updatedAt = now;
    }

    public void cancel(Instant now) {
        this.status = SalesOrderStatus.CANCELLED;
        this.cancelledAt = now;
        this.updatedAt = now;
    }

    public String getNumber() {
        return number;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public SalesOrderStatus getStatus() {
        return status;
    }

    public String getCurrency() {
        return currency;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getFulfilledAt() {
        return fulfilledAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
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
