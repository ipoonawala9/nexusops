package com.nexusops.directory.web;

import com.nexusops.directory.PartyService;
import com.nexusops.directory.PartyView;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/parties")
class PartyController {

    private final PartyService parties;

    PartyController(PartyService parties) {
        this.parties = parties;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('directory.party.read')")
    PartyView get(@PathVariable UUID id) {
        return parties.get(id);
    }
}
