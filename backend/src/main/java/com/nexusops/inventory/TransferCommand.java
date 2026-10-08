package com.nexusops.inventory;

import java.math.BigDecimal;
import java.util.UUID;

/** D8. */
public record TransferCommand(UUID productId, UUID fromWarehouseId, UUID toWarehouseId, BigDecimal quantity,
        String note) {}
