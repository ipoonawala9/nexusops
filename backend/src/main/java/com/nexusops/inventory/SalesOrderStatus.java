package com.nexusops.inventory;

public enum SalesOrderStatus {
    DRAFT, CONFIRMED, FULFILLED, CANCELLED;

    boolean cancellable() {
        return this == DRAFT || this == CONFIRMED;
    }
}
