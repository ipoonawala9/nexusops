package com.nexusops.crm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Per-currency running totals; never adds different currencies. Null currency (no amount) is ignored. */
final class MoneySums {

    private final Map<String, BigDecimal> sums = new TreeMap<>();

    void add(String currency, BigDecimal amount) {
        if (currency != null && amount != null) {
            sums.merge(currency, amount, BigDecimal::add);
        }
    }

    List<MoneyTotal> totals() {
        return sums.entrySet().stream()
                .map(e -> new MoneyTotal(e.getKey(), e.getValue().setScale(4, RoundingMode.HALF_UP))).toList();
    }
}
