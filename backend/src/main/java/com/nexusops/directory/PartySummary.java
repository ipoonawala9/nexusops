package com.nexusops.directory;

import java.util.List;
import java.util.UUID;

/** A directory list row; {@code roles} are the ACTIVE roles the caller may see. */
public record PartySummary(UUID id, PartyKind kind, String name, String email, String phone, String domain,
        PartyRef organization, List<PartyRoleType> roles, boolean archived) {}
