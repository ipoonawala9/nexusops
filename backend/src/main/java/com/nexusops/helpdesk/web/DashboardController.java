package com.nexusops.helpdesk.web;

import com.nexusops.helpdesk.DashboardView;
import com.nexusops.helpdesk.TicketService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("helpDeskDashboardController")
@RequestMapping("/api/v1/helpdesk/dashboard")
class DashboardController {

    private final TicketService tickets;

    DashboardController(TicketService tickets) {
        this.tickets = tickets;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('helpdesk.ticket.read')")
    DashboardView helpDeskDashboard() {
        return tickets.dashboard();
    }
}
