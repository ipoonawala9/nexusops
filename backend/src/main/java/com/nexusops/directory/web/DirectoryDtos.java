package com.nexusops.directory.web;

import com.nexusops.directory.OrganizationCommand;
import com.nexusops.directory.PersonCommand;
import java.util.UUID;

/** Request bodies. Field rules live in PartyService so API and service report identical messages. */
final class DirectoryDtos {

    private DirectoryDtos() {}

    record PersonRequest(String firstName, String lastName, String jobTitle, UUID organizationId, String email,
            String phone, String duplicateReason, Long version) {
        PersonCommand command() {
            return new PersonCommand(firstName, lastName, jobTitle, organizationId, email, phone, duplicateReason);
        }
    }

    record OrganizationRequest(String name, String domain, String website, String email, String phone,
            String duplicateReason, Long version) {
        OrganizationCommand command() {
            return new OrganizationCommand(name, domain, website, email, phone, duplicateReason);
        }
    }

    record PartyRoleRequest(com.nexusops.directory.RoleStatus status, java.time.LocalDate since, String employeeNumber) {
        com.nexusops.directory.PartyRoleCommand command() {
            return new com.nexusops.directory.PartyRoleCommand(status, since, employeeNumber);
        }
    }
}
