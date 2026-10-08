package com.nexusops.inventory;

import com.nexusops.directory.PartyRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SalesOrderSummary(UUID id, String number, PartyRef customer, WarehouseRef warehouse,
        SalesOrderStatus status, String currency, BigDecimal total, int lineCount, Instant createdAt) {}
