package com.nexusops.inventory.domain;

import com.nexusops.inventory.PurchaseOrderStatus;
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
@Table(name = "purchase_orders")
public class PurchaseOrder extends TenantOwnedEntity {

    @Column(nullable = false, updatable = false)
    private String number;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PurchaseOrderStatus status;

    @Column(nullable = false)
    private String currency;

    @Column(name = "expected_on")
    private LocalDate expectedOn;

    private String notes;

    @Column(name = "ordered_at")
    private Instant orderedAt;

    @Column(name = "received_at")
    private Instant receivedAt;

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

    protected PurchaseOrder() {}

    public PurchaseOrder(UUID id, String number, UUID supplierId, UUID warehouseId, String currency,
            LocalDate expectedOn, String notes, UUID createdBy) {
        super(id);
        this.number = number;
        this.status = PurchaseOrderStatus.DRAFT;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        apply(supplierId, warehouseId, currency, expectedOn, notes);
    }

    public void apply(UUID newSupplier, UUID newWarehouse, String newCurrency, LocalDate newExpected, String newNotes) {
        this.supplierId = newSupplier;
        this.warehouseId = newWarehouse;
        this.currency = newCurrency;
        this.expectedOn = newExpected;
        this.notes = newNotes;
        this.updatedAt = Instant.now();
    }

    public void order(Instant now) {
        this.status = PurchaseOrderStatus.ORDERED;
        this.orderedAt = now;
        this.updatedAt = now;
    }

    public void cancel(Instant now) {
        this.status = PurchaseOrderStatus.CANCELLED;
        this.cancelledAt = now;
        this.updatedAt = now;
    }

    /** After a receipt: RECEIVED when every line is complete, else PARTIALLY_RECEIVED. */
    public void received(boolean complete, Instant now) {
        this.status = complete ? PurchaseOrderStatus.RECEIVED : PurchaseOrderStatus.PARTIALLY_RECEIVED;
        this.receivedAt = complete ? now : null;
        this.updatedAt = now;
    }

    public String getNumber() {
        return number;
    }

    public UUID getSupplierId() {
        return supplierId;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public PurchaseOrderStatus getStatus() {
        return status;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getExpectedOn() {
        return expectedOn;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getOrderedAt() {
        return orderedAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
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
