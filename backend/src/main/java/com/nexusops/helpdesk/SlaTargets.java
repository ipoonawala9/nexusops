package com.nexusops.helpdesk;

/** One priority's SLA targets in minutes (D8). */
public record SlaTargets(int firstResponseMinutes, int resolutionMinutes) {}
