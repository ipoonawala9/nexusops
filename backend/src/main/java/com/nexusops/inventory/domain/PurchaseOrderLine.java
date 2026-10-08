package com.nexusops.inventory.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "purchase_order_lines")
public class PurchaseOrderLine extends TenantOwnedEntity {

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "received_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal receivedQuantity;

    @Column(name = "unit_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitCost;

    protected PurchaseOrderLine() {}

    public PurchaseOrderLine(UUID id, UUID orderId, int lineNo, UUID productId, BigDecimal quantity,
            BigDecimal unitCost) {
        super(id);
        this.orderId = orderId;
        this.lineNo = lineNo;
        this.productId = productId;
        this.quantity = quantity;
        this.receivedQuantity = BigDecimal.ZERO;
        this.unitCost = unitCost;
    }

    public void receive(BigDecimal amount) {
        this.receivedQuantity = receivedQuantity.add(amount);
    }

    public BigDecimal getRemaining() {
        return quantity.subtract(receivedQuantity);
    }

    public UUID getOrderId() {
        return orderId;
    }

    public int getLineNo() {
        return lineNo;
    }

    public UUID getProductId() {
        return productId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getReceivedQuantity() {
        return receivedQuantity;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }
}
