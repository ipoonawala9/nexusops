package com.nexusops.collaboration.web;

import com.nexusops.collaboration.ActivityCommand;
import com.nexusops.collaboration.ActivityType;
import com.nexusops.collaboration.TaskCommand;
import com.nexusops.collaboration.TaskPriority;
import com.nexusops.collaboration.TaskStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

final class CollaborationDtos {

    private CollaborationDtos() {}

    record ActivityRequest(String subjectType, UUID subjectId, ActivityType type, String summary, String body,
            Instant occurredAt) {
        ActivityCommand command() {
            return new ActivityCommand(subjectType, subjectId, type, summary, body, occurredAt);
        }
    }

    record TaskRequest(String title, String description, TaskPriority priority, LocalDate dueOn, UUID assigneeId,
            String subjectType, UUID subjectId, Long version) {
        TaskCommand command() {
            return new TaskCommand(title, description, priority, dueOn, assigneeId, subjectType, subjectId);
        }
    }

    record TaskStatusRequest(TaskStatus status) {}
}
