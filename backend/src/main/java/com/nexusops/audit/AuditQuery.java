package com.nexusops.audit;

import java.util.UUID;

public record AuditQuery(String action, String entityType, UUID actorId, String from, String to, Integer page,
        Integer size) {}
