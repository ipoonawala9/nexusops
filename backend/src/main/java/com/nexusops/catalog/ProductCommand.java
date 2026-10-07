package com.nexusops.catalog;

import java.math.BigDecimal;

/** Raw input; ProductService validates it. Kind defaults to GOODS and unit to "each". */
public record ProductCommand(String sku, String name, String description, ProductKind kind, String unit,
        BigDecimal listPrice, String currency) {}
