package com.nexusops.helpdesk;

import java.time.Instant;

public record SlaView(Instant firstResponseDueAt, Instant firstRespondedAt, SlaState firstResponseState,
        Instant resolutionDueAt, Instant resolvedAt, SlaState resolutionState, Instant pausedAt) {}
