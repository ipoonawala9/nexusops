package com.nexusops.inventory.web;

import com.nexusops.inventory.AdjustCommand;
import com.nexusops.inventory.PurchaseLineCommand;
import com.nexusops.inventory.PurchaseOrderCommand;
import com.nexusops.inventory.ReceiptCommand;
import com.nexusops.inventory.SalesLineCommand;
import com.nexusops.inventory.SalesOrderCommand;
import com.nexusops.inventory.TransferCommand;
import com.nexusops.inventory.WarehouseCommand;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

    record PurchaseOrderRequest(UUID supplierId, UUID warehouseId, String currency, LocalDate expectedOn,
            String notes, List<PurchaseLineCommand> lines, Long version) {
        PurchaseOrderCommand command() {
            return new PurchaseOrderCommand(supplierId, warehouseId, currency, expectedOn, notes, lines);
        }
    }

    record SalesOrderRequest(UUID customerId, UUID warehouseId, String currency, String notes,
            List<SalesLineCommand> lines, Long version) {
        SalesOrderCommand command() {
            return new SalesOrderCommand(customerId, warehouseId, currency, notes, lines);
        }
    }

    record VersionRequest(Long version) {}

    record ReceiptRequest(List<ReceiptCommand.Line> lines, Long version) {}
}
