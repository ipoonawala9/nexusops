package com.nexusops.inventory;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record DraftOrdersCommand(List<Item> items) {

    public record Item(UUID productId, UUID warehouseId, BigDecimal quantity) {}
}
