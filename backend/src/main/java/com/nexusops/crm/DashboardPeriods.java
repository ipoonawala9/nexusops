package com.nexusops.crm;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Calendar periods in the workspace time zone (a month starts at local midnight on the 1st). */
final class DashboardPeriods {

    private DashboardPeriods() {}

    static Instant monthStart(Instant now, ZoneId zone) {
        return today(now, zone).withDayOfMonth(1).atStartOfDay(zone).toInstant();
    }

    static LocalDate today(Instant now, ZoneId zone) {
        return LocalDate.ofInstant(now, zone);
    }
}
