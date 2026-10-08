package com.nexusops.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.shared.web.ApiProblem;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DecimalsTest {

    private static void refused(Runnable call, String message) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiProblem.class,
                p -> assertThat(p.errors().getFirst().message()).isEqualTo(message));
    }

    @Test
    void nonNegativeKeepsNullAndZero() {
        assertThat(Decimals.nonNegative(null, "x", "neg")).isNull();
        assertThat(Decimals.nonNegative(BigDecimal.ZERO, "x", "neg")).isEqualByComparingTo("0");
        assertThat(Decimals.nonNegative(new BigDecimal("12.3400"), "x", "neg")).isEqualByComparingTo("12.34");
    }

    @Test
    void positiveIsRequiredAndAboveZero() {
        refused(() -> Decimals.positive(null, "qty", "Enter a quantity greater than 0."), "Enter a quantity greater than 0.");
        refused(() -> Decimals.positive(BigDecimal.ZERO, "qty", "Enter a quantity greater than 0."),
                "Enter a quantity greater than 0.");
        assertThat(Decimals.positive(new BigDecimal("0.0001"), "qty", "m")).isEqualByComparingTo("0.0001");
    }

    @Test
    void scaleAndSizeAreBounded() {
        refused(() -> Decimals.nonNegative(new BigDecimal("-1"), "x", "Enter 0 or more."), "Enter 0 or more.");
        refused(() -> Decimals.nonNegative(new BigDecimal("1.23456"), "x", "neg"), "Use at most 4 decimal places.");
        refused(() -> Decimals.positive(new BigDecimal("1000000000000000"), "x", "m"), "Enter a smaller amount.");
        refused(() -> Decimals.positive(new BigDecimal("1E+16"), "x", "m"), "Enter a smaller amount.");
    }

    @Test
    void currencies() {
        assertThat(Currencies.parse(" inr ", "c")).isEqualTo("INR");
        assertThat(Currencies.parse(" ", "c")).isNull();
        refused(() -> Currencies.parse("EURO", "c"), "Use a 3-letter currency code like USD.");
        refused(() -> Currencies.parse("ZZZ", "c"), "Use a 3-letter currency code like USD.");
    }
}
