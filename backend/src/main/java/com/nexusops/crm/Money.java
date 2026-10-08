package com.nexusops.crm;

import com.nexusops.shared.Currencies;
import com.nexusops.shared.Decimals;
import com.nexusops.tenancy.TenantDirectory;
import java.math.BigDecimal;

/** Validation shared by lead values and opportunity amounts (same rules as product prices). */
final class Money {

    private Money() {}

    /** Null stays null; otherwise ≥ 0, ≤ 4 decimals, ≤ 15 integer digits. */
    static BigDecimal amount(BigDecimal value, String field) {
        return Decimals.nonNegative(value, field, "Enter an amount of 0 or more.");
    }

    /** The currency for a non-null amount: the given ISO code, or the workspace currency when blank. */
    static String currency(String raw, String field, TenantDirectory tenants) {
        String code = Currencies.parse(raw, field);
        return code != null ? code : tenants.currentSettings().currency();
    }
}
