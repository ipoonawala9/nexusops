package com.nexusops.identity.application;

/** Partial update: null fields are left unchanged. {@code status} is ACTIVE or DISABLED. */
public record UpdateUserCommand(String firstName, String lastName, String status) {}
