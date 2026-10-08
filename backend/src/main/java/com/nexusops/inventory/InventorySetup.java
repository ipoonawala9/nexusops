package com.nexusops.inventory;

import com.nexusops.tenancy.WorkspaceRegistered;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Every new workspace starts with its MAIN warehouse, in the signup transaction (D4). */
@Component
class InventorySetup {

    private final WarehouseService warehouses;

    InventorySetup(WarehouseService warehouses) {
        this.warehouses = warehouses;
    }

    @EventListener
    void on(WorkspaceRegistered event) {
        warehouses.seedDefault();
    }
}
