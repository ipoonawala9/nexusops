package com.nexusops.collaboration;

import java.time.Instant;
import java.util.UUID;

public record DocumentView(UUID id, String subjectType, UUID subjectId, String fileName, String contentType,
        long sizeBytes, String sha256, MemberRef uploadedBy, Instant createdAt) {}
