package com.nexusops.inventory.web;

import com.nexusops.inventory.AdjustCommand;
import com.nexusops.inventory.TransferCommand;
import com.nexusops.inventory.WarehouseCommand;
import java.math.BigDecimal;
import java.util.UUID;

final class InventoryDtos {

    private InventoryDtos() {}

    record WarehouseRequest(String code, String name, String address, Long version) {
        WarehouseCommand command() {
            return new WarehouseCommand(code, name, address);
        }
    }

    record AdjustRequest(UUID productId, UUID warehouseId, BigDecimal countedQuantity, String reason) {
        AdjustCommand command() {
            return new AdjustCommand(productId, warehouseId, countedQuantity, reason);
        }
    }

    record TransferRequest(UUID productId, UUID fromWarehouseId, UUID toWarehouseId, BigDecimal quantity, String note) {
        TransferCommand command() {
            return new TransferCommand(productId, fromWarehouseId, toWarehouseId, quantity, note);
        }
    }
}
