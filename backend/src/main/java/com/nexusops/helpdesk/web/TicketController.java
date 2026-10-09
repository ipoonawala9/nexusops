package com.nexusops.helpdesk.web;

import com.nexusops.helpdesk.AgentView;
import com.nexusops.helpdesk.MessagePosted;
import com.nexusops.helpdesk.MessageView;
import com.nexusops.helpdesk.Priority;
import com.nexusops.helpdesk.TicketMessageService;
import com.nexusops.helpdesk.TicketQuery;
import com.nexusops.helpdesk.TicketService;
import com.nexusops.helpdesk.TicketStatus;
import com.nexusops.helpdesk.TicketSummary;
import com.nexusops.helpdesk.TicketView;
import com.nexusops.helpdesk.web.HelpDeskDtos.AssignRequest;
import com.nexusops.helpdesk.web.HelpDeskDtos.MessageRequest;
import com.nexusops.helpdesk.web.HelpDeskDtos.StatusRequest;
import com.nexusops.helpdesk.web.HelpDeskDtos.TicketRequest;
import com.nexusops.shared.web.PageResponse;
import java.util.List;
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
@RequestMapping("/api/v1/helpdesk")
class TicketController {

    private final TicketService tickets;
    private final TicketMessageService messages;

    TicketController(TicketService tickets, TicketMessageService messages) {
        this.tickets = tickets;
        this.messages = messages;
    }

    @GetMapping("/tickets")
    @PreAuthorize("hasAuthority('helpdesk.ticket.read')")
    PageResponse<TicketSummary> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) List<TicketStatus> status, @RequestParam(required = false) Priority priority,
            @RequestParam(required = false) String assignee, @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID requesterId, @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) String sla, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return tickets.list(new TicketQuery(q, status, priority, assignee, categoryId, requesterId, productId, sla),
                page, size);
    }

    @GetMapping("/tickets/{id}")
    @PreAuthorize("hasAuthority('helpdesk.ticket.read')")
    TicketView get(@PathVariable UUID id) {
        return tickets.get(id);
    }

    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('helpdesk.ticket.manage')")
    TicketView create(@RequestBody TicketRequest request) {
        return tickets.create(request.command());
    }

    @PutMapping("/tickets/{id}")
    @PreAuthorize("hasAuthority('helpdesk.ticket.manage')")
    TicketView update(@PathVariable UUID id, @RequestBody TicketRequest request) {
        return tickets.update(id, request.command(), request.version());
    }

    @PostMapping("/tickets/{id}/assign")
    @PreAuthorize("hasAuthority('helpdesk.ticket.assign')")
    TicketView assign(@PathVariable UUID id, @RequestBody AssignRequest request) {
        return tickets.assign(id, request.assigneeId(), request.version());
    }

    @PostMapping("/tickets/{id}/status")
    @PreAuthorize("hasAuthority('helpdesk.ticket.resolve')")
    TicketView status(@PathVariable UUID id, @RequestBody StatusRequest request) {
        return tickets.changeStatus(id, request.status(), request.note(), request.version());
    }

    @GetMapping("/tickets/{id}/messages")
    @PreAuthorize("hasAuthority('helpdesk.ticket.read')")
    List<MessageView> messages(@PathVariable UUID id) {
        return messages.list(id);
    }

    @PostMapping("/tickets/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('helpdesk.ticket.manage')")
    MessagePosted post(@PathVariable UUID id, @RequestBody MessageRequest request) {
        return messages.post(id, request.command());
    }

    @GetMapping("/agents")
    @PreAuthorize("hasAuthority('helpdesk.ticket.read')")
    List<AgentView> agents(@RequestParam(required = false) String q) {
        return tickets.agents(q);
    }
}
