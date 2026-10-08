package com.nexusops.inventory;

import java.math.BigDecimal;
import java.util.UUID;

public record ReorderRuleCommand(UUID productId, UUID warehouseId, BigDecimal minQuantity, BigDecimal maxQuantity,
        UUID supplierId) {}
