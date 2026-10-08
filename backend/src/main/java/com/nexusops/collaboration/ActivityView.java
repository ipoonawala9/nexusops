package com.nexusops.collaboration;

import java.time.Instant;
import java.util.UUID;

public record ActivityView(UUID id, String subjectType, UUID subjectId, ActivityType type, String summary, String body,
        Instant occurredAt, MemberRef author, Instant createdAt, SubjectRef subject) {}
