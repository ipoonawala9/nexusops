package com.nexusops.directory.web;

import com.nexusops.directory.PartyKind;
import com.nexusops.directory.PartyQuery;
import com.nexusops.directory.PartyRoleType;
import com.nexusops.directory.PartyService;
import com.nexusops.directory.PartySummary;
import com.nexusops.directory.PartyView;
import com.nexusops.directory.web.DirectoryDtos.PartyRoleRequest;
import com.nexusops.shared.web.PageResponse;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/parties")
class PartyController {

    private final PartyService parties;

    PartyController(PartyService parties) {
        this.parties = parties;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('directory.party.read')")
    PageResponse<PartySummary> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) PartyKind kind, @RequestParam(required = false) PartyRoleType role,
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(defaultValue = "false") boolean archived, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return parties.list(new PartyQuery(q, kind, role, organizationId, archived), page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('directory.party.read')")
    PartyView get(@PathVariable UUID id) {
        return parties.get(id);
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('directory.party.manage')")
    PartyView archive(@PathVariable UUID id) {
        return parties.archive(id);
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('directory.party.manage')")
    PartyView restore(@PathVariable UUID id) {
        return parties.restore(id);
    }

    /** Either manage permission passes here; PartyService requires the specific one for the role (403). */
    @PutMapping("/{id}/roles/{role}")
    @PreAuthorize("hasAnyAuthority('directory.party.manage', 'directory.employee.manage')")
    PartyView setRole(@PathVariable UUID id, @PathVariable PartyRoleType role, @RequestBody PartyRoleRequest request) {
        return parties.setRole(id, role, request.command());
    }
}
