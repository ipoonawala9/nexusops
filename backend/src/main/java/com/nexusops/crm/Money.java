package com.nexusops.crm;

import com.nexusops.shared.web.ApiProblem;
import com.nexusops.tenancy.TenantDirectory;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;

/** Validation shared by lead values and opportunity amounts (same rules as product prices). */
final class Money {

    private static final int MAX_INTEGER_DIGITS = 15;

    private Money() {}

    /** Null stays null; otherwise ≥ 0, ≤ 4 decimals, ≤ 15 integer digits. */
    static BigDecimal amount(BigDecimal value, String field) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0) {
            throw ApiProblem.badRequestField(field, "Enter an amount of 0 or more.");
        }
        if (value.stripTrailingZeros().scale() > 4) {
            throw ApiProblem.badRequestField(field, "Use at most 4 decimal places.");
        }
        if (value.precision() - value.scale() > MAX_INTEGER_DIGITS) {
            throw ApiProblem.badRequestField(field, "Enter a smaller amount.");
        }
        return value;
    }

    /** The currency for a non-null amount: the given ISO code, or the workspace currency when blank. */
    static String currency(String raw, String field, TenantDirectory tenants) {
        if (raw == null || raw.isBlank()) {
            return tenants.currentSettings().currency();
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
