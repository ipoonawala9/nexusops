package com.nexusops.crm.web;

import com.nexusops.crm.LeadConversionService;
import com.nexusops.crm.LeadQuery;
import com.nexusops.crm.LeadService;
import com.nexusops.crm.LeadSource;
import com.nexusops.crm.LeadView;
import com.nexusops.crm.web.CrmDtos.ConversionRequest;
import com.nexusops.crm.web.CrmDtos.LeadRequest;
import com.nexusops.crm.web.CrmDtos.LeadStatusRequest;
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
@RequestMapping("/api/v1/leads")
class LeadController {

    private final LeadService leads;
    private final LeadConversionService conversions;

    LeadController(LeadService leads, LeadConversionService conversions) {
        this.leads = leads;
        this.conversions = conversions;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('crm.lead.read')")
    PageResponse<LeadView> list(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
            @RequestParam(required = false) String owner, @RequestParam(required = false) LeadSource source,
            @RequestParam(required = false) UUID partyId, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return leads.list(new LeadQuery(q, status, owner, source, partyId), page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('crm.lead.read')")
    LeadView get(@PathVariable UUID id) {
        return leads.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('crm.lead.manage')")
    LeadView create(@RequestBody LeadRequest request) {
        return leads.create(request.command());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('crm.lead.manage')")
    LeadView update(@PathVariable UUID id, @RequestBody LeadRequest request) {
        return leads.update(id, request.command(), request.version());
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('crm.lead.manage')")
    LeadView status(@PathVariable UUID id, @RequestBody LeadStatusRequest request) {
        return leads.changeStatus(id, request.status(), request.reason(), request.version());
    }

    @PostMapping("/{id}/convert")
    @PreAuthorize("hasAuthority('crm.lead.manage')")
    LeadView convert(@PathVariable UUID id, @RequestBody ConversionRequest request) {
        return conversions.convert(id, request.command(), request.version());
    }
}
