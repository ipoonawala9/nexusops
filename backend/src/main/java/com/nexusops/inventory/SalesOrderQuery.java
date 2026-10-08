package com.nexusops.inventory;

import java.util.UUID;

public record SalesOrderQuery(String q, SalesOrderStatus status, UUID customerId, UUID warehouseId) {}
