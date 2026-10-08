package com.nexusops.inventory;

import java.util.UUID;

public record PurchaseOrderQuery(String q, PurchaseOrderStatus status, UUID supplierId, UUID warehouseId) {}
