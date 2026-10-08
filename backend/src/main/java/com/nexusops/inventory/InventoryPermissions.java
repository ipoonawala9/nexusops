package com.nexusops.inventory;

/** Permission codes of the Inventory module (V18). All have module_code INVENTORY. */
public final class InventoryPermissions {

    public static final String STOCK_READ = "inventory.stock.read";
    public static final String STOCK_ADJUST = "inventory.stock.adjust";
    public static final String WAREHOUSE_MANAGE = "inventory.warehouse.manage";
    public static final String PURCHASE_READ = "inventory.purchase.read";
    public static final String PURCHASE_MANAGE = "inventory.purchase.manage";
    public static final String ORDER_READ = "inventory.order.read";
    public static final String ORDER_MANAGE = "inventory.order.manage";
    public static final String REORDER_MANAGE = "inventory.reorder.manage";

    private InventoryPermissions() {}
}
