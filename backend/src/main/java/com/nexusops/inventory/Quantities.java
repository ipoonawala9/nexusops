package com.nexusops.inventory;

import com.nexusops.shared.Decimals;
import com.nexusops.shared.web.ApiProblem;
import java.math.BigDecimal;

/** Quantity rules (D3). */
final class Quantities {

    static final String TOO_LARGE = "Enter a smaller quantity.";

    private Quantities() {}

    static BigDecimal positive(BigDecimal value, String field) {
        return Decimals.positive(value, field, "Enter a quantity greater than 0.", TOO_LARGE);
    }

    static BigDecimal count(BigDecimal value, String field) {
        if (value == null) {
            throw ApiProblem.badRequestField(field, "Enter 0 or more.");
        }
        return Decimals.nonNegative(value, field, "Enter 0 or more.", TOO_LARGE);
    }
}
