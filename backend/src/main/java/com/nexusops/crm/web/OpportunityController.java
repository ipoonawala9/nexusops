package com.nexusops.crm.web;

import com.nexusops.crm.OpportunityQuery;
import com.nexusops.crm.OpportunityService;
import com.nexusops.crm.OpportunityStatus;
import com.nexusops.crm.OpportunityView;
import com.nexusops.crm.web.CrmDtos.OpportunityRequest;
import com.nexusops.crm.web.CrmDtos.StageMoveRequest;
import com.nexusops.shared.web.PageResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/opportunities")
class OpportunityController {

    private final OpportunityService opportunities;

    OpportunityController(OpportunityService opportunities) {
        this.opportunities = opportunities;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('crm.opportunity.read')")
    PageResponse<OpportunityView> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) OpportunityStatus status, @RequestParam(required = false) UUID stageId,
            @RequestParam(required = false) UUID accountId, @RequestParam(required = false) UUID contactId,
            @RequestParam(required = false) String owner, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return opportunities.list(new OpportunityQuery(q, status, stageId, accountId, contactId, owner), page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('crm.opportunity.read')")
    OpportunityView get(@PathVariable UUID id) {
        return opportunities.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('crm.opportunity.manage')")
    OpportunityView create(@RequestBody OpportunityRequest request) {
        return opportunities.create(request.command());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('crm.opportunity.manage')")
    OpportunityView update(@PathVariable UUID id, @RequestBody OpportunityRequest request) {
        return opportunities.update(id, request.command(), request.version());
    }

    @PostMapping("/{id}/stage")
    @PreAuthorize("hasAuthority('crm.opportunity.manage')")
    OpportunityView move(@PathVariable UUID id, @RequestBody StageMoveRequest request) {
        return opportunities.moveStage(id, request.stageId(), request.lostReason(), request.version());
    }
}
