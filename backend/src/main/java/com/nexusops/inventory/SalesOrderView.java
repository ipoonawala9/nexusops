package com.nexusops.inventory;

import com.nexusops.collaboration.MemberRef;
import com.nexusops.directory.PartyRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SalesOrderView(UUID id, String number, PartyRef customer, WarehouseRef warehouse,
        SalesOrderStatus status, String currency, String notes, List<SalesLineView> lines, BigDecimal total,
        Instant confirmedAt, Instant fulfilledAt, Instant cancelledAt, MemberRef createdBy, Instant createdAt,
        Instant updatedAt, long version) {}
