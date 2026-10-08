package com.nexusops.inventory;

import java.math.BigDecimal;
import java.util.UUID;

public record PurchaseLineView(UUID id, int lineNo, ProductRef product, BigDecimal quantity,
        BigDecimal receivedQuantity, BigDecimal remainingQuantity, BigDecimal unitCost, BigDecimal lineTotal) {}
