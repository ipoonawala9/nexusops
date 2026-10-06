package com.nexusops.directory.domain;

import java.util.UUID;

/** Validated, normalized person fields (PartyService builds these). */
public record PersonDetails(String firstName, String lastName, String jobTitle, UUID organizationId, String email,
        String phone) {}
