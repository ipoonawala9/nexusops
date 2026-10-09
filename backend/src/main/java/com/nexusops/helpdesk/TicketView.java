package com.nexusops.helpdesk;

import com.nexusops.collaboration.MemberRef;
import com.nexusops.directory.PartyRef;
import java.time.Instant;
import java.util.UUID;

public record TicketView(UUID id, String number, String subject, String description, PartyRef requester,
        TicketProductRef product, LinkedRecord linked, CategoryRef category, Priority priority, Channel channel,
        MemberRef assignee, TicketStatus status, SlaView sla, String resolutionNote, int reopenCount,
        Instant closedAt, MemberRef createdBy, Instant createdAt, Instant updatedAt, long version) {}
