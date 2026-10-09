package com.nexusops.helpdesk;

/** Where a ticket stands against one SLA target (D8). */
public enum SlaState {
    ON_TRACK, AT_RISK, PAUSED, MET, BREACHED
}
