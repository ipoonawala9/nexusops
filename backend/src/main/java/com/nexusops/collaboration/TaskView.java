package com.nexusops.collaboration;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TaskView(UUID id, String title, String description, TaskStatus status, TaskPriority priority,
        LocalDate dueOn, MemberRef assignee, SubjectRef subject, MemberRef createdBy, Instant completedAt,
        Instant createdAt, Instant updatedAt, long version) {}
