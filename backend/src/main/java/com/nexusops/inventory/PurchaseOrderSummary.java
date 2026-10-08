package com.nexusops.inventory;

import com.nexusops.directory.PartyRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PurchaseOrderSummary(UUID id, String number, PartyRef supplier, WarehouseRef warehouse,
        PurchaseOrderStatus status, String currency, BigDecimal total, int lineCount, LocalDate expectedOn,
        Instant createdAt) {}
