package com.nexusops.inventory;

import java.util.UUID;

/** A stock level's identity. Natural order = lock order (product, then warehouse), so locks can't deadlock. */
public record StockKey(UUID productId, UUID warehouseId) implements Comparable<StockKey> {

    @Override
    public int compareTo(StockKey other) {
        int byProduct = productId.compareTo(other.productId);
        return byProduct != 0 ? byProduct : warehouseId.compareTo(other.warehouseId);
    }
}
