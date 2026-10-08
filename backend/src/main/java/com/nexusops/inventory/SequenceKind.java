package com.nexusops.inventory;

/** Per-tenant document number series. */
public enum SequenceKind {
    PURCHASE_ORDER("PO-"), SALES_ORDER("SO-");

    private final String prefix;

    SequenceKind(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }
}
