package com.nexusops.inventory;

import java.math.BigDecimal;
import java.util.UUID;

public record PurchaseLineCommand(UUID productId, BigDecimal quantity, BigDecimal unitCost) {}
