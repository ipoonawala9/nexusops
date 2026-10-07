package com.nexusops.crm.web;

import com.nexusops.collaboration.AssigneeView;
import com.nexusops.identity.Members;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Members who can own leads and opportunities (first 20 active matches). */
@RestController
class OwnerController {

    private final Members members;

    OwnerController(Members members) {
        this.members = members;
    }

    @GetMapping("/api/v1/crm/owners")
    @PreAuthorize("hasAnyAuthority('crm.lead.manage', 'crm.opportunity.manage')")
    List<AssigneeView> owners(@RequestParam(required = false) String q) {
        return members.searchActive(q, 20).stream().map(m -> new AssigneeView(m.id(), m.name(), m.email())).toList();
    }
}
