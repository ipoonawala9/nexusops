package com.nexusops.inventory.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Rows are created by StockLedger (INSERT … ON CONFLICT DO NOTHING) and only ever changed under a row lock. */
@Entity
@Table(name = "stock_levels")
public class StockLevel extends TenantOwnedEntity {

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @Column(name = "on_hand", nullable = false, precision = 19, scale = 4)
    private BigDecimal onHand;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal reserved;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected StockLevel() {}

    public void changeOnHand(BigDecimal delta) {
        this.onHand = onHand.add(delta);
        this.updatedAt = Instant.now();
    }

    public void reserve(BigDecimal quantity) {
        this.reserved = reserved.add(quantity);
        this.updatedAt = Instant.now();
    }

    public void release(BigDecimal quantity) {
        this.reserved = reserved.subtract(quantity);
        this.updatedAt = Instant.now();
    }

    public BigDecimal getAvailable() {
        return onHand.subtract(reserved);
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public BigDecimal getOnHand() {
        return onHand;
    }

    public BigDecimal getReserved() {
        return reserved;
    }
}
