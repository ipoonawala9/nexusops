package com.nexusops.inventory;

import com.nexusops.collaboration.MemberRef;
import com.nexusops.directory.PartyRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PurchaseOrderView(UUID id, String number, PartyRef supplier, WarehouseRef warehouse,
        PurchaseOrderStatus status, String currency, LocalDate expectedOn, String notes, List<PurchaseLineView> lines,
        BigDecimal total, Instant orderedAt, Instant receivedAt, Instant cancelledAt, MemberRef createdBy,
        Instant createdAt, Instant updatedAt, long version) {}
