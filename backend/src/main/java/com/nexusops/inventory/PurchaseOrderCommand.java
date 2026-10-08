package com.nexusops.inventory;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Raw input; currency defaults to the workspace currency. */
public record PurchaseOrderCommand(UUID supplierId, UUID warehouseId, String currency, LocalDate expectedOn,
        String notes, List<PurchaseLineCommand> lines) {}
