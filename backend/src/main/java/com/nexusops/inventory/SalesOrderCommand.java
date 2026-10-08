package com.nexusops.inventory;

import java.util.List;
import java.util.UUID;

/** Raw input; currency defaults to the workspace currency. */
public record SalesOrderCommand(UUID customerId, UUID warehouseId, String currency, String notes,
        List<SalesLineCommand> lines) {}
