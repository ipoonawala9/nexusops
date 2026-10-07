package com.nexusops.crm.web;

import com.nexusops.crm.BoardView;
import com.nexusops.crm.OpportunityService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class BoardController {

    private final OpportunityService opportunities;

    BoardController(OpportunityService opportunities) {
        this.opportunities = opportunities;
    }

    @GetMapping("/api/v1/crm/pipeline/board")
    @PreAuthorize("hasAuthority('crm.opportunity.read')")
    BoardView board(@RequestParam(required = false) String owner) {
        return opportunities.board(owner);
    }
}
