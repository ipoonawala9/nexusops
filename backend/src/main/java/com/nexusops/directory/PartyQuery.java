package com.nexusops.directory;

import java.util.UUID;

/** Directory list filters. {@code archived=true} lists only archived parties. */
public record PartyQuery(String q, PartyKind kind, PartyRoleType role, UUID organizationId, boolean archived) {}
