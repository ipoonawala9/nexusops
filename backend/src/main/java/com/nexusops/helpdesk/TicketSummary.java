package com.nexusops.helpdesk;

import com.nexusops.collaboration.MemberRef;
import com.nexusops.directory.PartyRef;
import java.time.Instant;
import java.util.UUID;

public record TicketSummary(UUID id, String number, String subject, PartyRef requester, CategoryRef category,
        Priority priority, TicketStatus status, MemberRef assignee, SlaView sla, Instant createdAt,
        Instant updatedAt) {}
