package com.nexusops.directory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A party with its roles. EMPLOYEE roles appear only to callers with directory.employee.read. */
public record PartyView(UUID id, PartyKind kind, String name, String firstName, String lastName, String jobTitle,
        PartyRef organization, String email, String phone, String domain, String website, List<PartyRoleView> roles,
        String duplicateReason, Instant archivedAt, Instant createdAt, Instant updatedAt, long version) {}
