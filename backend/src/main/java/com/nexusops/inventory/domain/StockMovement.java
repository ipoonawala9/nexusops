package com.nexusops.inventory.domain;

import com.nexusops.inventory.MovementKind;
import com.nexusops.inventory.ReferenceType;
import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** One ledger row (append-only: the app role has no UPDATE or DELETE grant). */
@Entity
@Immutable
@Table(name = "stock_movements")
public class StockMovement extends TenantOwnedEntity {

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MovementKind kind;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "on_hand_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal onHandAfter;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false)
    private ReferenceType referenceType;

    @Column(name = "reference_id", nullable = false)
    private UUID referenceId;

    private String reason;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected StockMovement() {}

    public StockMovement(UUID id, UUID productId, UUID warehouseId, MovementKind kind, BigDecimal quantity,
            BigDecimal onHandAfter, ReferenceType referenceType, UUID referenceId, String reason, UUID actorId,
            Instant occurredAt) {
        super(id);
        this.productId = productId;
        this.warehouseId = warehouseId;
        this.kind = kind;
        this.quantity = quantity;
        this.onHandAfter = onHandAfter;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.reason = reason;
        this.actorId = actorId;
        this.occurredAt = occurredAt;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public MovementKind getKind() {
        return kind;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getOnHandAfter() {
        return onHandAfter;
    }

    public ReferenceType getReferenceType() {
        return referenceType;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public String getReason() {
        return reason;
    }

    public UUID getActorId() {
        return actorId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
