package com.nexusops.shared;

import com.nexusops.shared.web.ApiProblem;
import java.util.Locale;

/** Trims and length-checks free-text input. Failures are 400 field errors. */
public final class Text {

    private Text() {}

    public static String required(String raw, int max, String field) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty() || value.length() > max) {
            throw ApiProblem.badRequestField(field, "Enter between 1 and " + max + " characters.");
        }
        return value;
    }

    /** Blank input becomes null. */
    public static String optional(String raw, int max, String field) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > max) {
            throw ApiProblem.badRequestField(field, "Enter at most " + max + " characters.");
        }
        return value;
    }

    /** A lower-case LIKE pattern for "contains raw", with \ % _ escaped (use '\' as the escape character). */
    public static String containsPattern(String raw) {
        String escaped = raw.strip().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
