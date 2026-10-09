package com.nexusops.helpdesk;

import java.util.UUID;

public record TicketProductRef(UUID id, String sku, String name) {}
