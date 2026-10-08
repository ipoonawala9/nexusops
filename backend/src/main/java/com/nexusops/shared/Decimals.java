package com.nexusops.shared;

import com.nexusops.shared.web.ApiProblem;
import java.math.BigDecimal;

/** Validation of numeric(19,4) inputs: amounts, quantities, counts. Failures are 400 field errors. */
public final class Decimals {

    private static final int MAX_INTEGER_DIGITS = 15;
    private static final String TOO_LARGE = "Enter a smaller amount.";

    private Decimals() {}

    /** Null stays null; otherwise ≥ 0, at most 4 decimals and 15 integer digits. */
    public static BigDecimal nonNegative(BigDecimal value, String field, String negativeMessage) {
        return nonNegative(value, field, negativeMessage, TOO_LARGE);
    }

    /** As {@link #nonNegative(BigDecimal, String, String)}, saying {@code tooLargeMessage} past 15 integer digits. */
    public static BigDecimal nonNegative(BigDecimal value, String field, String negativeMessage,
            String tooLargeMessage) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0) {
            throw ApiProblem.badRequestField(field, negativeMessage);
        }
        return bounded(value, field, tooLargeMessage);
    }

    /** Required and > 0, at most 4 decimals and 15 integer digits. */
    public static BigDecimal positive(BigDecimal value, String field, String message) {
        return positive(value, field, message, TOO_LARGE);
    }

    /** As {@link #positive(BigDecimal, String, String)}, saying {@code tooLargeMessage} past 15 integer digits. */
    public static BigDecimal positive(BigDecimal value, String field, String message, String tooLargeMessage) {
        if (value == null || value.signum() <= 0) {
            throw ApiProblem.badRequestField(field, message);
        }
        return bounded(value, field, tooLargeMessage);
    }

    private static BigDecimal bounded(BigDecimal value, String field, String tooLargeMessage) {
        if (value.stripTrailingZeros().scale() > 4) {
            throw ApiProblem.badRequestField(field, "Use at most 4 decimal places.");
        }
        if (value.precision() - value.scale() > MAX_INTEGER_DIGITS) {
            throw ApiProblem.badRequestField(field, tooLargeMessage);
        }
        return value;
    }
}
