package com.nexusops.inventory;

import java.util.UUID;

public record StockQuery(String q, UUID warehouseId, boolean belowMin) {}
