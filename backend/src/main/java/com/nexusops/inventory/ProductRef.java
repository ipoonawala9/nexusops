package com.nexusops.inventory;

import java.util.UUID;

public record ProductRef(UUID id, String sku, String name, String unit) {}
