package com.nexusops.helpdesk;

import com.nexusops.collaboration.MemberRef;
import java.time.Instant;
import java.util.UUID;

public record ArticleView(UUID id, String title, String body, CategoryRef category, ArticleStatus status,
        MemberRef author, Instant publishedAt, Instant createdAt, Instant updatedAt, long version) {}
