package com.nexusops.collaboration.web;

import com.nexusops.collaboration.ActivityCommand;
import com.nexusops.collaboration.ActivityType;
import java.time.Instant;
import java.util.UUID;

final class CollaborationDtos {

    private CollaborationDtos() {}

    record ActivityRequest(String subjectType, UUID subjectId, ActivityType type, String summary, String body,
            Instant occurredAt) {
        ActivityCommand command() {
            return new ActivityCommand(subjectType, subjectId, type, summary, body, occurredAt);
        }
    }
}
