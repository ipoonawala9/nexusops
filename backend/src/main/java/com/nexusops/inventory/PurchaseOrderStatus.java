package com.nexusops.inventory;

public enum PurchaseOrderStatus {
    DRAFT, ORDERED, PARTIALLY_RECEIVED, RECEIVED, CANCELLED;

    boolean receivable() {
        return this == ORDERED || this == PARTIALLY_RECEIVED;
    }

    /** A partly received order can be closed: the rest won't arrive, what arrived stays in stock. */
    boolean cancellable() {
        return this == DRAFT || this == ORDERED || this == PARTIALLY_RECEIVED;
    }
}
