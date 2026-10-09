package com.nexusops.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SlaClockTest {

    static final Instant T0 = Instant.parse("2026-10-09T09:00:00Z");
    static final SlaTargets NORMAL = new SlaTargets(480, 2880);

    static Instant at(long minutes) {
        return T0.plus(Duration.ofMinutes(minutes));
    }

    @Test
    void dueTimesComeFromTheTargets() {
        assertThat(SlaClock.firstResponseDue(T0, NORMAL)).isEqualTo(at(480));
        assertThat(SlaClock.resolutionDue(T0, 0, NORMAL)).isEqualTo(at(2880));
        assertThat(SlaClock.resolutionDue(T0, 3600, NORMAL)).isEqualTo(at(2880 + 60));
    }

    @Test
    void pausingMovesTheResolutionDueTimeByThePausedDuration() {
        // paused at 1 h, resumed at 3 h 30 min: 2 h 30 min of waiting on the customer
        long paused = SlaClock.pausedSecondsAfterResume(0, at(60), at(210));
        assertThat(paused).isEqualTo(150 * 60);
        assertThat(SlaClock.resolutionDue(T0, paused, NORMAL)).isEqualTo(at(2880 + 150));
        // a second pause adds to the first
        assertThat(SlaClock.pausedSecondsAfterResume(paused, at(300), at(310))).isEqualTo(160 * 60);
        // a clock that went backwards never shortens the target
        assertThat(SlaClock.pausedSecondsAfterResume(paused, at(300), at(290))).isEqualTo(paused);
    }

    @Test
    void stateWhileRunning() {
        Instant due = at(480);
        assertThat(SlaClock.state(due, null, at(100), 480, null)).isEqualTo(SlaState.ON_TRACK);
        // 25 % of 480 = 120 minutes left is the at-risk line
        assertThat(SlaClock.state(due, null, at(359), 480, null)).isEqualTo(SlaState.ON_TRACK);
        assertThat(SlaClock.state(due, null, at(361), 480, null)).isEqualTo(SlaState.AT_RISK);
        assertThat(SlaClock.state(due, null, at(480), 480, null)).isEqualTo(SlaState.AT_RISK);
        assertThat(SlaClock.state(due, null, at(481), 480, null)).isEqualTo(SlaState.BREACHED);
    }

    @Test
    void doneIsMetOrBreachedForGood() {
        Instant due = at(480);
        assertThat(SlaClock.state(due, at(480), at(9999), 480, null)).isEqualTo(SlaState.MET);
        assertThat(SlaClock.state(due, at(481), at(481), 480, null)).isEqualTo(SlaState.BREACHED);
    }

    @Test
    void waitingOnTheCustomerIsPausedUnlessItWasAlreadyLate() {
        Instant due = at(2880);
        assertThat(SlaClock.state(due, null, at(5000), 2880, at(100))).isEqualTo(SlaState.PAUSED);
        assertThat(SlaClock.state(due, null, at(5000), 2880, at(2900))).isEqualTo(SlaState.BREACHED);
    }

    @Test
    void reopenRestartsTheResolutionClockOnly() {
        // resolved at 10 h within its 48 h target, reopened at 30 h: the new resolution due is 30 h + 48 h
        Instant reopenedAt = at(30 * 60);
        assertThat(SlaClock.resolutionDue(reopenedAt, 0, NORMAL)).isEqualTo(at(30 * 60 + 2880));
        // the first-response due time depends only on creation
        assertThat(SlaClock.firstResponseDue(T0, NORMAL)).isEqualTo(at(480));
    }
}
