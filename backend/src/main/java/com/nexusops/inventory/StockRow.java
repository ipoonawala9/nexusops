package com.nexusops.inventory;

import java.math.BigDecimal;
import java.util.UUID;

/** A product × warehouse pair that has a stock level or a reorder rule (rule fields null without one). */
public record StockRow(ProductRef product, WarehouseRef warehouse, BigDecimal onHand, BigDecimal reserved,
        BigDecimal available, BigDecimal onOrder, UUID ruleId, BigDecimal minQuantity, BigDecimal maxQuantity,
        boolean belowMin) {}
