package com.nexusops.collaboration;

import java.time.Instant;
import java.util.UUID;

/** occurredAt defaults to now. */
public record ActivityCommand(String subjectType, UUID subjectId, ActivityType type, String summary, String body,
        Instant occurredAt) {}
