package com.nexusops.inventory.web;

import com.nexusops.inventory.WarehouseCommand;

final class InventoryDtos {

    private InventoryDtos() {}

    record WarehouseRequest(String code, String name, String address, Long version) {
        WarehouseCommand command() {
            return new WarehouseCommand(code, name, address);
        }
    }
}
