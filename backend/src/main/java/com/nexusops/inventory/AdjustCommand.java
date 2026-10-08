package com.nexusops.inventory;

import java.math.BigDecimal;
import java.util.UUID;

/** A stock count (D7). */
public record AdjustCommand(UUID productId, UUID warehouseId, BigDecimal countedQuantity, String reason) {}
