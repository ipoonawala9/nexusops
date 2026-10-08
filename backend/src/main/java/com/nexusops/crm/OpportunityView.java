package com.nexusops.crm;

import com.nexusops.collaboration.MemberRef;
import com.nexusops.directory.PartyRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record OpportunityView(UUID id, String name, PartyRef account, PartyRef contact, StageRef stage,
        OpportunityStatus status, BigDecimal amount, String currency, LocalDate expectedCloseOn, MemberRef owner,
        UUID leadId, String description, String lostReason, Instant closedAt, MemberRef createdBy, Instant createdAt,
        Instant updatedAt, long version) {}
