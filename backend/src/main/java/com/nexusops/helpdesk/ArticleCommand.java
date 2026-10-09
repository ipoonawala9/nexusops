package com.nexusops.helpdesk;

import java.util.UUID;

public record ArticleCommand(String title, String body, UUID categoryId) {}
