package com.nexusops.helpdesk;

import com.nexusops.collaboration.SearchHit;
import com.nexusops.collaboration.SubjectRef;
import com.nexusops.collaboration.SubjectResolver;
import com.nexusops.directory.PartyBrief;
import com.nexusops.directory.PartyService;
import com.nexusops.helpdesk.domain.Ticket;
import com.nexusops.helpdesk.domain.TicketRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * Tickets as collaboration subjects (type TICKET, D11): a closed ticket counts as archived. Reads PartyService rather
 * than TicketService, which depends on Subjects (a cycle otherwise).
 */
@Component
class TicketSubjects implements SubjectResolver {

    static final String TYPE = "TICKET";

    private final TicketRepository tickets;
    private final PartyService parties;

    TicketSubjects(TicketRepository tickets, PartyService parties) {
        this.tickets = tickets;
        this.parties = parties;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String readPermission() {
        return HelpDeskPermissions.TICKET_READ;
    }

    @Override
    public Optional<SubjectRef> find(UUID id) {
        return tickets.findById(id).map(TicketSubjects::ref);
    }

    @Override
    public Map<UUID, SubjectRef> findAll(Collection<UUID> ids) {
        return tickets.findAllById(ids).stream().collect(Collectors.toMap(Ticket::getId, TicketSubjects::ref));
    }

    @Override
    public List<SearchHit> search(String pattern, int limit) {
        Specification<Ticket> spec = (root, cq, cb) -> cb.or(cb.like(cb.lower(root.get("number")), pattern, '\\'),
                cb.like(cb.lower(root.get("subject")), pattern, '\\'));
        List<Ticket> found = tickets.findAll(spec,
                PageRequest.of(0, limit, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")))).getContent();
        Map<UUID, PartyBrief> names = parties.briefs(found.stream().map(Ticket::getRequesterId).collect(Collectors.toSet()));
        return found.stream().map(t -> new SearchHit(TYPE, t.getId(), label(t),
                names.containsKey(t.getRequesterId()) ? names.get(t.getRequesterId()).name() : null,
                t.getStatus() == TicketStatus.CLOSED)).toList();
    }

    private static SubjectRef ref(Ticket t) {
        return new SubjectRef(TYPE, t.getId(), label(t), t.getStatus() == TicketStatus.CLOSED);
    }

    static String label(Ticket t) {
        return t.getNumber() + " · " + t.getSubject();
    }
}
