package com.nexusops.inventory;

import java.util.List;

public record InventoryOverview(long belowMinimum, long purchaseOrdersAwaitingReceipt,
        long salesOrdersAwaitingFulfilment, List<MovementView> recentMovements) {}
