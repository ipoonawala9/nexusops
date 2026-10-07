package com.nexusops.directory;

import java.util.UUID;

/** A party as other modules reference it: enough to label, type-check and reject archived records. */
public record PartyBrief(UUID id, PartyKind kind, String name, UUID organizationId, boolean archived) {}
