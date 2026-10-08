package com.nexusops.inventory;

import com.nexusops.collaboration.MemberRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MovementView(UUID id, ProductRef product, WarehouseRef warehouse, MovementKind kind, BigDecimal quantity,
        BigDecimal onHandAfter, ReferenceType referenceType, UUID referenceId, String reason, MemberRef actor,
        Instant occurredAt) {}
