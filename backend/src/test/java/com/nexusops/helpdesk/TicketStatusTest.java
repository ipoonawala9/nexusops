package com.nexusops.helpdesk;

import static com.nexusops.helpdesk.TicketStatus.CLOSED;
import static com.nexusops.helpdesk.TicketStatus.NEW;
import static com.nexusops.helpdesk.TicketStatus.OPEN;
import static com.nexusops.helpdesk.TicketStatus.PENDING;
import static com.nexusops.helpdesk.TicketStatus.RESOLVED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class TicketStatusTest {

    @Test
    void transitionsFollowTheWorkflow() {
        Map<TicketStatus, Set<TicketStatus>> allowed = Map.of(
                NEW, EnumSet.of(OPEN, PENDING, RESOLVED),
                OPEN, EnumSet.of(PENDING, RESOLVED),
                PENDING, EnumSet.of(OPEN, RESOLVED),
                RESOLVED, EnumSet.of(OPEN, CLOSED),
                CLOSED, EnumSet.noneOf(TicketStatus.class));
        for (TicketStatus from : TicketStatus.values()) {
            Set<TicketStatus> actual = Arrays.stream(TicketStatus.values()).filter(from::canMoveTo)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(TicketStatus.class)));
            assertThat(actual).as(from.name()).isEqualTo(allowed.get(from));
        }
    }

    @Test
    void openMeansNotYetResolved() {
        assertThat(Arrays.stream(TicketStatus.values()).filter(TicketStatus::isOpen))
                .containsExactly(NEW, OPEN, PENDING);
    }
}
