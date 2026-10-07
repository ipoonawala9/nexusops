package com.nexusops.directory;

/** Raw input; PartyService validates and normalizes it. */
public record OrganizationCommand(String name, String domain, String website, String email, String phone,
        String duplicateReason) {}
