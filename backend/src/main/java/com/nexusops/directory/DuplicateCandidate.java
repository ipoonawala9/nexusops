package com.nexusops.directory;

import java.util.UUID;

/** An existing record that a create or edit would duplicate (409 problem member `duplicates`). */
public record DuplicateCandidate(UUID id, PartyKind kind, String name, String email, String domain, boolean archived) {}
