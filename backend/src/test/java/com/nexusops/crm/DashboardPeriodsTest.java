package com.nexusops.crm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class DashboardPeriodsTest {

    final Instant lateOctoberUtc = Instant.parse("2026-10-31T20:00:00Z");

    @Test
    void theMonthStartsInTheWorkspaceTimeZone() {
        // 20:00 UTC on 31 October is already 1 November in India
        assertThat(DashboardPeriods.monthStart(lateOctoberUtc, ZoneId.of("Asia/Kolkata")))
                .isEqualTo(Instant.parse("2026-10-31T18:30:00Z"));
        assertThat(DashboardPeriods.monthStart(lateOctoberUtc, ZoneId.of("UTC")))
                .isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
    }

    @Test
    void todayIsTheWorkspaceDate() {
        assertThat(DashboardPeriods.today(lateOctoberUtc, ZoneId.of("Asia/Kolkata"))).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(DashboardPeriods.today(lateOctoberUtc, ZoneId.of("America/New_York"))).isEqualTo(LocalDate.of(2026, 10, 31));
    }
}
