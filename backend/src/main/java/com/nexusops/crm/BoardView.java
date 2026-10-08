package com.nexusops.crm;

import java.util.List;

/** One column per stage, in board order. Won and Lost columns hold deals closed in the last 30 days. */
public record BoardView(List<BoardColumn> columns) {

    /** {@code opportunities}: the first 50, soonest expected close first; {@code count} and totals cover all. */
    public record BoardColumn(StageView stage, long count, List<MoneyTotal> totals, List<MoneyTotal> weighted,
            List<OpportunitySummary> opportunities) {}
}
