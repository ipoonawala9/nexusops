package com.nexusops.collaboration.domain;

import com.nexusops.collaboration.TaskPriority;
import java.time.LocalDate;
import java.util.UUID;

/** Validated task fields (TaskService builds these). */
public record TaskDetails(String title, String description, TaskPriority priority, LocalDate dueOn, UUID assigneeId,
        String subjectType, UUID subjectId) {}
