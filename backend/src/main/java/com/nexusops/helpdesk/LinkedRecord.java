package com.nexusops.helpdesk;

import java.util.UUID;

/** Any collaboration record the ticket concerns; {@code label} is null when the caller may not read that type. */
public record LinkedRecord(String type, UUID id, String label) {}
