package com.nexusops.helpdesk;

import com.nexusops.collaboration.SubjectKey;
import com.nexusops.collaboration.SubjectRelations;
import com.nexusops.helpdesk.domain.Ticket;
import com.nexusops.helpdesk.domain.TicketRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/** A party's and a product's timelines include their tickets (D14). */
@Component
class TicketRelations implements SubjectRelations {

    private static final int LIMIT = 100;
    private static final Sort ORDER = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));

    private final TicketRepository tickets;

    TicketRelations(TicketRepository tickets) {
        this.tickets = tickets;
    }

    @Override
    public List<SubjectKey> related(String type, UUID id) {
        String field = switch (type) {
            case "PARTY" -> "requesterId";
            case "PRODUCT" -> "productId";
            default -> null;
        };
        if (field == null) {
            return List.of();
        }
        Specification<Ticket> spec = (root, cq, cb) -> cb.equal(root.get(field), id);
        return tickets.findAll(spec, PageRequest.of(0, LIMIT, ORDER)).stream()
                .map(t -> new SubjectKey(TicketSubjects.TYPE, t.getId())).toList();
    }
}
