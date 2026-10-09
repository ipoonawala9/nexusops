package com.nexusops.helpdesk;

import java.time.Duration;
import java.time.Instant;

/**
 * The deterministic SLA rule (D8). First-response due = created + target. Resolution due = clock start (creation, or
 * the last reopen) + target + accumulated paused time; time spent waiting on the customer doesn't count.
 */
final class SlaClock {

    static final double AT_RISK_FRACTION = 0.25;

    private SlaClock() {}

    static Instant firstResponseDue(Instant createdAt, SlaTargets targets) {
        return createdAt.plus(Duration.ofMinutes(targets.firstResponseMinutes()));
    }

    static Instant resolutionDue(Instant clockStartedAt, long pausedSeconds, SlaTargets targets) {
        return clockStartedAt.plus(Duration.ofMinutes(targets.resolutionMinutes())).plusSeconds(pausedSeconds);
    }

    /** Accumulated pause after leaving PENDING; never negative, whatever the clocks did. */
    static long pausedSecondsAfterResume(long pausedSeconds, Instant pausedAt, Instant now) {
        return pausedSeconds + Math.max(0, Duration.between(pausedAt, now).toSeconds());
    }

    /**
     * {@code doneAt} null means not done yet; {@code pausedAt} null means the clock is running. A clock paused after its
     * due time had already passed stays breached: resuming can't give that time back.
     */
    static SlaState state(Instant due, Instant doneAt, Instant now, int targetMinutes, Instant pausedAt) {
        if (doneAt != null) {
            return doneAt.isAfter(due) ? SlaState.BREACHED : SlaState.MET;
        }
        if (pausedAt != null) {
            return pausedAt.isAfter(due) ? SlaState.BREACHED : SlaState.PAUSED;
        }
        if (now.isAfter(due)) {
            return SlaState.BREACHED;
        }
        long remainingSeconds = Duration.between(now, due).toSeconds();
        return remainingSeconds < targetMinutes * 60L * AT_RISK_FRACTION ? SlaState.AT_RISK : SlaState.ON_TRACK;
    }
}
