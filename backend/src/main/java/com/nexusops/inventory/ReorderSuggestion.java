package com.nexusops.inventory;

import com.nexusops.directory.PartyRef;
import java.math.BigDecimal;
import java.util.UUID;

/** One rule below its minimum, with the figures that explain it (D12). */
public record ReorderSuggestion(UUID ruleId, ProductRef product, WarehouseRef warehouse, BigDecimal available,
        BigDecimal onOrder, BigDecimal minQuantity, BigDecimal maxQuantity, PartyRef supplier,
        BigDecimal usedLast30Days, BigDecimal averageDailyUsage, BigDecimal daysOfCover, BigDecimal suggestedQuantity,
        String explanation) {}
