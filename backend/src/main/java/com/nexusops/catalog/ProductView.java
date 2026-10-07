package com.nexusops.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductView(UUID id, String sku, String name, String description, ProductKind kind, String unit,
        BigDecimal listPrice, String currency, Instant archivedAt, Instant createdAt, Instant updatedAt, long version) {}
