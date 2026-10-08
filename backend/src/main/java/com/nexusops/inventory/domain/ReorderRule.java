package com.nexusops.inventory.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reorder_rules")
public class ReorderRule extends TenantOwnedEntity {

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @Column(name = "min_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal minQuantity;

    @Column(name = "max_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal maxQuantity;

    @Column(name = "supplier_id")
    private UUID supplierId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected ReorderRule() {}

    public ReorderRule(UUID id, UUID productId, UUID warehouseId) {
        super(id);
        this.productId = productId;
        this.warehouseId = warehouseId;
        this.createdAt = Instant.now();
    }

    public void apply(BigDecimal min, BigDecimal max, UUID supplier) {
        this.minQuantity = min;
        this.maxQuantity = max;
        this.supplierId = supplier;
        this.updatedAt = Instant.now();
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public BigDecimal getMinQuantity() {
        return minQuantity;
    }

    public BigDecimal getMaxQuantity() {
        return maxQuantity;
    }

    public UUID getSupplierId() {
        return supplierId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
