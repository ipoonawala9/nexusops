package com.nexusops.tenancy;

import com.nexusops.shared.web.ApiProblem;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Workspace slugs: trimmed, lower-cased, 3–40 chars of [a-z0-9] with single inner hyphens, not reserved. */
public final class Slug {

    private static final Pattern VALID = Pattern.compile("^[a-z0-9](-?[a-z0-9]){2,39}$");
    private static final Set<String> RESERVED = Set.of(
            "api", "app", "admin", "www", "platform", "auth", "login", "signup", "static", "assets",
            "mail", "support", "help", "status", "nexusops");

    private Slug() {}

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiProblem.badRequestField("slug", "Workspace URL is required.");
        }
        String slug = raw.trim().toLowerCase(Locale.ROOT);
        if (!VALID.matcher(slug).matches()) {
            throw ApiProblem.badRequestField("slug",
                    "Use 3–40 lowercase letters, digits or single hyphens, starting and ending with a letter or digit.");
        }
        if (RESERVED.contains(slug)) {
            throw ApiProblem.badRequestField("slug", "This workspace URL is reserved.");
        }
        return slug;
    }

    public static Optional<String> tryNormalize(String raw) {
        try {
            return Optional.of(normalize(raw));
        } catch (ApiProblem e) {
            return Optional.empty();
        }
    }
}
