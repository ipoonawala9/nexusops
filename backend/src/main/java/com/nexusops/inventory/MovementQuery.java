package com.nexusops.inventory;

import java.util.UUID;

public record MovementQuery(UUID productId, UUID warehouseId, MovementKind kind) {}
