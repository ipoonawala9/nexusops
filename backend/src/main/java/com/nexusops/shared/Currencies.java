package com.nexusops.shared;

import com.nexusops.shared.web.ApiProblem;
import java.util.Currency;
import java.util.Locale;

public final class Currencies {

    private Currencies() {}

    /** Null for blank input; otherwise the upper-case ISO-4217 code, or a 400 field error. */
    public static String parse(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String code = raw.strip().toUpperCase(Locale.ROOT);
        try {
            if (code.length() == 3 && Currency.getInstance(code) != null) {
                return code;
            }
        } catch (IllegalArgumentException unknown) {
            // falls through to the field error
        }
        throw ApiProblem.badRequestField(field, "Use a 3-letter currency code like USD.");
    }
}
