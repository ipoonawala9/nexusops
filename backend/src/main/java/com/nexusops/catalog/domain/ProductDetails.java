package com.nexusops.catalog.domain;

import com.nexusops.catalog.ProductKind;
import java.math.BigDecimal;

/** Validated product fields (ProductService builds these). */
public record ProductDetails(String sku, String name, String description, ProductKind kind, String unit,
        BigDecimal listPrice, String currency) {}
