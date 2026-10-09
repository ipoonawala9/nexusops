package com.nexusops.helpdesk;

import java.util.UUID;

/** Raw input. {@code assigneeId} is honoured on create only (assignment has its own action); null priority → NORMAL, null channel → PHONE. */
public record TicketCommand(String subject, String description, UUID requesterId, UUID productId, String linkedType,
        UUID linkedId, UUID categoryId, Priority priority, Channel channel, UUID assigneeId) {}
