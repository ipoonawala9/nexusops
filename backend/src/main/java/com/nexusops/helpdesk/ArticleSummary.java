package com.nexusops.helpdesk;

import java.time.Instant;
import java.util.UUID;

/** {@code excerpt}: the first 200 characters of the body, on one line. */
public record ArticleSummary(UUID id, String title, String excerpt, CategoryRef category, ArticleStatus status,
        Instant publishedAt, Instant updatedAt) {}
