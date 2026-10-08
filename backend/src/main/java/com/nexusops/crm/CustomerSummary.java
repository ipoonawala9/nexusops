package com.nexusops.crm;

import com.nexusops.directory.PartyView;
import java.util.List;

/** Customer 360 header figures (D8): opportunities where the party is the account, and leads converted into it. */
public record CustomerSummary(PartyView party, long openCount, List<MoneyTotal> openValue,
        List<MoneyTotal> weightedValue, long wonCount, List<MoneyTotal> wonValue, long lostCount, long leadCount) {}
