package com.nexusops.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.shared.web.ApiProblem;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class QuantitiesTest {

    private static void refused(Runnable call, String field, String message) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiProblem.class, p -> {
            assertThat(p.errors().getFirst().field()).isEqualTo(field);
            assertThat(p.errors().getFirst().message()).isEqualTo(message);
        });
    }

    @Test
    void tooLargeQuantitiesSayQuantity() {
        refused(() -> Quantities.positive(new BigDecimal("1000000000000000"), "quantity"), "quantity",
                "Enter a smaller quantity.");
        refused(() -> Quantities.count(new BigDecimal("1E+16"), "countedQuantity"), "countedQuantity",
                "Enter a smaller quantity.");
        assertThat(Quantities.positive(new BigDecimal("999999999999999.9999"), "quantity"))
                .isEqualByComparingTo("999999999999999.9999");
    }

    @Test
    void moneyKeepsSayingAmount() {
        assertThatThrownBy(() -> com.nexusops.shared.Decimals.nonNegative(new BigDecimal("1E+16"), "unitCost", "neg"))
                .isInstanceOfSatisfying(ApiProblem.class,
                        p -> assertThat(p.errors().getFirst().message()).isEqualTo("Enter a smaller amount."));
    }
}
