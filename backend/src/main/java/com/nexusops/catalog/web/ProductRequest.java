package com.nexusops.catalog.web;

import com.nexusops.catalog.ProductCommand;
import com.nexusops.catalog.ProductKind;
import java.math.BigDecimal;

record ProductRequest(String sku, String name, String description, ProductKind kind, String unit, BigDecimal listPrice,
        String currency, Long version) {

    ProductCommand command() {
        return new ProductCommand(sku, name, description, kind, unit, listPrice, currency);
    }
}
