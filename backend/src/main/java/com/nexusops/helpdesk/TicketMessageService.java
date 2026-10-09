package com.nexusops.helpdesk;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.collaboration.MemberRef;
import com.nexusops.directory.PartyService;
import com.nexusops.directory.PartyView;
import com.nexusops.helpdesk.domain.Ticket;
import com.nexusops.helpdesk.domain.TicketMessage;
import com.nexusops.helpdesk.domain.TicketMessageRepository;
import com.nexusops.helpdesk.domain.TicketRepository;
import com.nexusops.identity.Members;
import com.nexusops.notifications.MailRequested;
import com.nexusops.notifications.OutgoingMail;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.tenancy.TenantDirectory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The conversation (D9). A public reply is the first response and is emailed to the requester; a customer message
 * logged by an agent resumes a PENDING ticket; an internal note changes nothing but the record. Posts on one ticket
 * serialize on its row lock, so concurrent agents don't fail each other.
 */
@Service
public class TicketMessageService {

    private final TicketRepository tickets;
    private final TicketMessageRepository messages;
    private final TicketService ticketService;
    private final PartyService parties;
    private final Members members;
    private final TenantDirectory tenants;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    TicketMessageService(TicketRepository tickets, TicketMessageRepository messages, TicketService ticketService,
            PartyService parties, Members members, TenantDirectory tenants, AuditService audit,
            ApplicationEventPublisher events) {
        this.tickets = tickets;
        this.messages = messages;
        this.ticketService = ticketService;
        this.parties = parties;
        this.members = members;
        this.tenants = tenants;
        this.audit = audit;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<MessageView> list(UUID ticketId) {
        ticketService.find(ticketId);
        List<TicketMessage> found = messages.findByTicketIdOrderByCreatedAtAscIdAsc(ticketId);
        Map<UUID, Members.Member> authors = members.findAll(found.stream().map(TicketMessage::getAuthorId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return found.stream()
                .map(m -> view(m, m.getAuthorId() == null ? null : authors.get(m.getAuthorId()))).toList();
    }

    @Transactional
    public MessagePosted post(UUID ticketId, MessageCommand command) {
        if (command.kind() == null) {
            throw ApiProblem.badRequestField("kind", "Choose a reply, a note or a customer message.");
        }
        String body = Text.required(command.body(), 10_000, "body");
        TenantContext.requireTenantId();
        Ticket ticket = tickets.findForUpdate(ticketId).orElseThrow(() -> ApiProblem.notFound(TicketService.NOT_FOUND));
        TicketService.requireNotClosed(ticket);
        Instant now = Instant.now();
        String emailedTo = null;
        switch (command.kind()) {
            case PUBLIC_REPLY -> {
                ticket.recordFirstResponse(now);
                emailedTo = requesterEmail(ticket);
            }
            case CUSTOMER_MESSAGE -> {
                if (ticket.getStatus() == TicketStatus.PENDING) {
                    ticketService.moveTo(ticket, TicketStatus.OPEN, null, now);
                } else {
                    ticket.touch(now);
                }
            }
            case INTERNAL_NOTE -> ticket.touch(now);
        }
        tickets.flush();
        UUID author = TenantContext.userId().orElse(null);
        TicketMessage message = messages.saveAndFlush(new TicketMessage(Ids.newId(), ticket.getId(), command.kind(),
                body, author, emailedTo, now));
        audit.record(AuditEntry.of(switch (command.kind()) {
            case PUBLIC_REPLY -> "TicketReplied";
            case INTERNAL_NOTE -> "TicketNoteAdded";
            case CUSTOMER_MESSAGE -> "TicketCustomerMessage";
        }, "Ticket", ticket.getId()).withAfter(Map.of("messageId", message.getId().toString(),
                "emailed", emailedTo != null)));
        Members.Member writer = author == null ? null : members.findAll(List.of(author)).get(author);
        if (emailedTo != null) {
            sendReply(ticket, emailedTo, body, writer);
        }
        return new MessagePosted(view(message, writer), ticketService.view(ticket));
    }

    /** The person's email, or the organisation's; null when the requester has none (D9). */
    private String requesterEmail(Ticket ticket) {
        PartyView requester = parties.get(ticket.getRequesterId());
        String email = requester.email();
        return email == null || email.isBlank() ? null : email;
    }

    /** From the workspace's name (D9); the customer's answer goes to the agent who wrote, when known. */
    private void sendReply(Ticket ticket, String to, String body, Members.Member writer) {
        String workspace = tenants.current().name();
        events.publishEvent(new MailRequested(new OutgoingMail(to,
                "[" + ticket.getNumber() + "] " + ticket.getSubject(), """
                %s

                —
                %s, %s
                Reference: %s
                """.formatted(body, writer == null ? "The support team" : writer.name(), workspace,
                ticket.getNumber()), workspace, writer == null ? null : writer.email())));
    }

    private static MessageView view(TicketMessage m, Members.Member author) {
        return new MessageView(m.getId(), m.getKind(), m.getBody(),
                author == null ? null : new MemberRef(author.id(), author.name()), m.getEmailedTo(), m.getCreatedAt());
    }
}
