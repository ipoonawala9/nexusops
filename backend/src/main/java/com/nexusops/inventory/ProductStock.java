package com.nexusops.inventory;

import java.math.BigDecimal;
import java.util.List;

/** A product's stock in every active warehouse (zeros included) and in total. */
public record ProductStock(ProductRef product, List<Level> levels, BigDecimal onHand, BigDecimal reserved,
        BigDecimal available) {

    public record Level(WarehouseRef warehouse, BigDecimal onHand, BigDecimal reserved, BigDecimal available) {}
}
