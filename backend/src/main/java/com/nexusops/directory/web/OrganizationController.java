package com.nexusops.directory.web;

import com.nexusops.directory.PartyService;
import com.nexusops.directory.PartyView;
import com.nexusops.directory.web.DirectoryDtos.OrganizationRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations")
class OrganizationController {

    private final PartyService parties;

    OrganizationController(PartyService parties) {
        this.parties = parties;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('directory.party.manage')")
    PartyView create(@RequestBody OrganizationRequest request) {
        return parties.createOrganization(request.command());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('directory.party.manage')")
    PartyView update(@PathVariable UUID id, @RequestBody OrganizationRequest request) {
        return parties.updateOrganization(id, request.command(), request.version());
    }
}
