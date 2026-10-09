package com.nexusops.helpdesk.web;

import com.nexusops.helpdesk.Priority;
import com.nexusops.helpdesk.SlaPolicyService;
import com.nexusops.helpdesk.SlaPolicyView;
import com.nexusops.helpdesk.web.HelpDeskDtos.SlaPolicyRequest;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/helpdesk/sla-policies")
class SlaPolicyController {

    private final SlaPolicyService policies;

    SlaPolicyController(SlaPolicyService policies) {
        this.policies = policies;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('helpdesk.ticket.read')")
    List<SlaPolicyView> list() {
        return policies.list();
    }

    @PutMapping("/{priority}")
    @PreAuthorize("hasAuthority('helpdesk.settings.manage')")
    SlaPolicyView update(@PathVariable Priority priority, @RequestBody SlaPolicyRequest request) {
        return policies.update(priority, request.command(), request.version());
    }
}
