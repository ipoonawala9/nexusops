package com.nexusops.directory.domain;

/** Validated, normalized organization fields (PartyService builds these). */
public record OrganizationDetails(String name, String domain, String website, String email, String phone) {}
