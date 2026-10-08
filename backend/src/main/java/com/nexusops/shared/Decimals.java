package com.nexusops.shared;

import com.nexusops.shared.web.ApiProblem;
import java.math.BigDecimal;

/** Validation of numeric(19,4) inputs: amounts, quantities, counts. Failures are 400 field errors. */
public final class Decimals {

    private static final int MAX_INTEGER_DIGITS = 15;

    private Decimals() {}

    /** Null stays null; otherwise ≥ 0, at most 4 decimals and 15 integer digits. */
    public static BigDecimal nonNegative(BigDecimal value, String field, String negativeMessage) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0) {
            throw ApiProblem.badRequestField(field, negativeMessage);
        }
        return bounded(value, field);
    }

    /** Required and > 0, at most 4 decimals and 15 integer digits. */
    public static BigDecimal positive(BigDecimal value, String field, String message) {
        if (value == null || value.signum() <= 0) {
            throw ApiProblem.badRequestField(field, message);
        }
        return bounded(value, field);
    }

    private static BigDecimal bounded(BigDecimal value, String field) {
        if (value.stripTrailingZeros().scale() > 4) {
            throw ApiProblem.badRequestField(field, "Use at most 4 decimal places.");
        }
        if (value.precision() - value.scale() > MAX_INTEGER_DIGITS) {
            throw ApiProblem.badRequestField(field, "Enter a smaller amount.");
        }
        return value;
    }
}
