package com.nexusops.crm;

import java.math.BigDecimal;

/** A sum in one currency. Amounts of different currencies are never added together. */
public record MoneyTotal(String currency, BigDecimal amount) {}
