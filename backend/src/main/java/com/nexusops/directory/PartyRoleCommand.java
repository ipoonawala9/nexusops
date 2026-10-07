package com.nexusops.directory;

import java.time.LocalDate;

/** Status defaults to ACTIVE; employeeNumber is for EMPLOYEE only. */
public record PartyRoleCommand(RoleStatus status, LocalDate since, String employeeNumber) {}
