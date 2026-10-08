package com.nexusops.crm.web;

import com.nexusops.crm.DashboardService;
import com.nexusops.crm.DashboardView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DashboardController {

    private final DashboardService dashboard;

    DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/api/v1/crm/dashboard")
    @PreAuthorize("hasAnyAuthority('crm.lead.read', 'crm.opportunity.read')")
    DashboardView dashboard(@RequestParam(required = false) String owner) {
        return dashboard.dashboard(owner);
    }
}
