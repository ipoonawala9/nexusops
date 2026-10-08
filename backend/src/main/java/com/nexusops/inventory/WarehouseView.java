package com.nexusops.inventory;

import java.time.Instant;
import java.util.UUID;

public record WarehouseView(UUID id, String code, String name, String address, Instant archivedAt, long version) {}
