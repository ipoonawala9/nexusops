package com.nexusops.collaboration;

import java.time.LocalDate;
import java.util.UUID;

/** Raw input. Priority defaults to NORMAL; subjectType and subjectId go together. */
public record TaskCommand(String title, String description, TaskPriority priority, LocalDate dueOn, UUID assigneeId,
        String subjectType, UUID subjectId) {}
