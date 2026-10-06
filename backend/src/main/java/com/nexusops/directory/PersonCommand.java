package com.nexusops.directory;

import java.util.UUID;

/** Raw input; PartyService validates and normalizes it. */
public record PersonCommand(String firstName, String lastName, String jobTitle, UUID organizationId, String email,
        String phone, String duplicateReason) {}
