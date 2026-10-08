package com.nexusops.inventory;

public enum PurchaseOrderStatus {
    DRAFT, ORDERED, PARTIALLY_RECEIVED, RECEIVED, CANCELLED;

    boolean receivable() {
        return this == ORDERED || this == PARTIALLY_RECEIVED;
    }

    boolean cancellable() {
        return this == DRAFT || this == ORDERED;
    }
}
