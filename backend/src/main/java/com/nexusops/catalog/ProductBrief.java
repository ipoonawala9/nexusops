package com.nexusops.catalog;

import java.math.BigDecimal;
import java.util.UUID;

/** A product as other modules reference it. */
public record ProductBrief(UUID id, String sku, String name, ProductKind kind, String unit, BigDecimal listPrice,
        String currency, boolean archived) {}
