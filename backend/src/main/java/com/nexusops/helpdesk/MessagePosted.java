package com.nexusops.helpdesk;

/** A posted message and the ticket as it is afterwards (status and SLA may have moved). */
public record MessagePosted(MessageView message, TicketView ticket) {}
