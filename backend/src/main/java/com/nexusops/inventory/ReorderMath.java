package com.nexusops.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** The deterministic reorder rule (D11–D12, blueprint §5.4: rules first). */
final class ReorderMath {

    static final BigDecimal WINDOW_DAYS = BigDecimal.valueOf(30);

    private ReorderMath() {}

    record Figures(BigDecimal available, BigDecimal onOrder, BigDecimal min, BigDecimal max,
            BigDecimal usedLast30Days) {

        BigDecimal position() {
            return available.add(onOrder);
        }

        boolean belowMin() {
            return position().compareTo(min) < 0;
        }

        BigDecimal averageDailyUsage() {
            return usedLast30Days.divide(WINDOW_DAYS, 4, RoundingMode.HALF_UP);
        }

        /** Null when nothing was used: no usage means no rate to divide by. */
        BigDecimal daysOfCover() {
            BigDecimal daily = averageDailyUsage();
            if (daily.signum() == 0) {
                return null;
            }
            return available.max(BigDecimal.ZERO).divide(daily, 1, RoundingMode.HALF_UP);
        }

        BigDecimal suggestedQuantity() {
            return max.subtract(position()).max(BigDecimal.ZERO);
        }

        String explanation() {
            StringBuilder text = new StringBuilder()
                    .append(plain(available)).append(" available, ")
                    .append(plain(onOrder)).append(" on order, below the minimum of ").append(plain(min)).append("; ");
            BigDecimal daily = averageDailyUsage();
            if (daily.signum() == 0) {
                text.append("no usage in the last 30 days; ");
            } else {
                long days = available.max(BigDecimal.ZERO).divide(daily, 4, RoundingMode.HALF_UP)
                        .setScale(0, RoundingMode.HALF_UP).longValue();
                text.append(plain(usedLast30Days)).append(" used in the last 30 days (about ").append(days)
                        .append(days == 1 ? " day" : " days").append(" of cover); ");
            }
            return text.append("order ").append(plain(suggestedQuantity())).append(" to reach ").append(plain(max))
                    .toString();
        }
    }

    static String plain(BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }
}
