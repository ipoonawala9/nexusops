package com.nexusops.inventory;

import java.math.BigDecimal;
import java.util.UUID;

/** {@code unitPrice} null → the product's list price when it is in the order's currency. */
public record SalesLineCommand(UUID productId, BigDecimal quantity, BigDecimal unitPrice) {}
