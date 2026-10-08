package com.nexusops.inventory;

import com.nexusops.directory.PartyRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ReorderRuleView(UUID id, ProductRef product, WarehouseRef warehouse, BigDecimal minQuantity,
        BigDecimal maxQuantity, PartyRef supplier, Instant updatedAt, long version) {}
