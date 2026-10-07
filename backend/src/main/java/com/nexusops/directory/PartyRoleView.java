package com.nexusops.directory;

import java.time.LocalDate;

public record PartyRoleView(PartyRoleType role, RoleStatus status, LocalDate since, String employeeNumber) {}
