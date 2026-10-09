package com.nexusops.helpdesk;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/** D15. Rates are 0–1 and null when nothing in the window can be measured. */
@Schema(name = "HelpDeskDashboardView")
public record DashboardView(Map<TicketStatus, Long> openByStatus, Map<Priority, Long> openByPriority, long unassigned,
        long breached, long atRisk, Last30Days last30Days) {

    @Schema(name = "HelpDeskLast30Days")
    public record Last30Days(long created, long resolved, Double averageFirstResponseMinutes,
            Double medianFirstResponseMinutes, Double averageResolutionMinutes, Double firstResponseMetRate,
            Double resolutionMetRate, Double reopenRate) {}
}
