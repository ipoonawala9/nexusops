package com.nexusops.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ReorderMathTest {

    private static ReorderMath.Figures figures(String available, String onOrder, String min, String max, String used) {
        return new ReorderMath.Figures(new BigDecimal(available), new BigDecimal(onOrder), new BigDecimal(min),
                new BigDecimal(max), new BigDecimal(used));
    }

    @Test
    void theSpecExampleExplainsItself() {
        ReorderMath.Figures f = figures("12", "0", "20", "60", "38");
        assertThat(f.belowMin()).isTrue();
        assertThat(f.averageDailyUsage()).isEqualByComparingTo("1.2667");
        assertThat(f.daysOfCover()).isEqualByComparingTo("9.5");
        assertThat(f.suggestedQuantity()).isEqualByComparingTo("48");
        assertThat(f.explanation()).isEqualTo("12 available, 0 on order, below the minimum of 20; "
                + "38 used in the last 30 days (about 9 days of cover); order 48 to reach 60");
    }

    @Test
    void onOrderCountsTowardsTheMinimumAndTheSuggestion() {
        assertThat(figures("12", "8", "20", "60", "0").belowMin()).isFalse();
        ReorderMath.Figures f = figures("12", "5", "20", "60", "0");
        assertThat(f.belowMin()).isTrue();
        assertThat(f.suggestedQuantity()).isEqualByComparingTo("43");
    }

    @Test
    void noUsageMeansNoDaysOfCover() {
        ReorderMath.Figures f = figures("3", "0", "5", "10", "0");
        assertThat(f.averageDailyUsage()).isEqualByComparingTo("0");
        assertThat(f.daysOfCover()).isNull();
        assertThat(f.explanation()).isEqualTo("3 available, 0 on order, below the minimum of 5; "
                + "no usage in the last 30 days; order 7 to reach 10");
    }

    @Test
    void fractionsAndSingularsReadNaturally() {
        ReorderMath.Figures f = figures("1.5", "0.25", "2", "4.5", "45");
        assertThat(f.daysOfCover()).isEqualByComparingTo("1.0");
        assertThat(f.suggestedQuantity()).isEqualByComparingTo("2.75");
        assertThat(f.explanation()).isEqualTo("1.5 available, 0.25 on order, below the minimum of 2; "
                + "45 used in the last 30 days (about 1 day of cover); order 2.75 to reach 4.5");
    }

    @Test
    void emptyShelvesHaveZeroDaysOfCoverAndTheSuggestionNeverGoesNegative() {
        assertThat(figures("0", "0", "5", "10", "30").daysOfCover()).isEqualByComparingTo("0");
        assertThat(figures("50", "20", "5", "10", "0").suggestedQuantity()).isEqualByComparingTo("0");
    }
}
