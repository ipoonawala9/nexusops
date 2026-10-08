package com.nexusops.crm;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** D14. A section is null when the caller can't read it. */
public record DashboardView(LeadStats leads, PipelineStats pipeline) {

    /** {@code open}: counts for NEW, CONTACTED, QUALIFIED. {@code conversionRate}: 0–1 over leads closed in 90 days. */
    public record LeadStats(Map<LeadStatus, Long> open, long newLast30Days, long converted90Days,
            long disqualified90Days, BigDecimal conversionRate) {}

    public record PipelineStats(List<StageStats> stages, ClosedStats wonThisMonth, ClosedStats lostThisMonth,
            List<OpportunitySummary> closingSoon) {}

    public record StageStats(StageView stage, long count, List<MoneyTotal> totals, List<MoneyTotal> weighted) {}

    public record ClosedStats(long count, List<MoneyTotal> totals) {}
}
