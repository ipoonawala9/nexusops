package com.nexusops.audit;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record AuditEventView(UUID id, Instant occurredAt, String actorType, UUID actorId, String action,
        String entityType, String entityId, String ip, String userAgent, String requestId, String correlationId,
        JsonNode before, JsonNode after, JsonNode metadata) {}
