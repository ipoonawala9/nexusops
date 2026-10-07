package com.nexusops.collaboration;

import java.util.UUID;

/** assignee: "me", "unassigned" or a user id; status: comma-separated TaskStatus names. */
public record TaskQuery(String assignee, String status, String subjectType, UUID subjectId, String q) {}
