package com.nexusops.helpdesk;

import java.util.List;
import java.util.UUID;

/** {@code statuses} empty → the open ones; {@code assignee} is a member id, "me" or "unassigned"; {@code sla} "breached" or "at_risk". */
public record TicketQuery(String q, List<TicketStatus> statuses, Priority priority, String assignee, UUID categoryId,
        UUID requesterId, UUID productId, String sla) {}
