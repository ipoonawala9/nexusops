package com.nexusops.crm;

import java.util.UUID;

/** {@code status}: comma-separated, default the open statuses. {@code owner}: me, unassigned or a member id.
 * {@code partyId}: leads converted into that person or organization. */
public record LeadQuery(String q, String status, String owner, LeadSource source, UUID partyId) {}
