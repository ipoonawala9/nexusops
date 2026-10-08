package com.nexusops.crm;

import com.nexusops.directory.PartySummary;
import java.util.List;

public record CustomerRow(PartySummary party, long openCount, List<MoneyTotal> openValue, long wonCount,
        List<MoneyTotal> wonValue) {}
