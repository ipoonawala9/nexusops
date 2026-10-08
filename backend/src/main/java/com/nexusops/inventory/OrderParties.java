package com.nexusops.inventory;

import com.nexusops.directory.DirectoryPermissions;
import com.nexusops.directory.PartyBrief;
import com.nexusops.directory.PartyRef;
import com.nexusops.directory.PartyService;
import com.nexusops.shared.security.CurrentAuthorities;
import com.nexusops.shared.web.ApiProblem;
import jakarta.persistence.criteria.Predicate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/** Suppliers and customers as orders see them. */
@Component
class OrderParties {

    static final String UNKNOWN = "Choose a person or organization in this workspace.";
    /** How many parties a name search considers when matching orders by their party's name. */
    static final int NAME_MATCHES = 50;

    private final PartyService parties;

    OrderParties(PartyService parties) {
        this.parties = parties;
    }

    /** A party referenced from a request body: required (400), readable by the caller (403), in this workspace (400). */
    PartyBrief resolve(UUID id, String field, String missingMessage) {
        if (id == null) {
            throw ApiProblem.badRequestField(field, missingMessage);
        }
        if (!CurrentAuthorities.has(DirectoryPermissions.PARTY_READ)) {
            throw ApiProblem.forbidden(Orders.FORBIDDEN);
        }
        PartyBrief party = parties.briefs(List.of(id)).get(id);
        if (party == null) {
            throw ApiProblem.badRequestField(field, UNKNOWN);
        }
        return party;
    }

    static void requireNotArchived(PartyBrief party) {
        if (party.archived()) {
            throw ApiProblem.conflict(Orders.ARCHIVED);
        }
    }

    /** At the moment of commitment (order, confirm) the party must still be active. */
    void requireNotArchived(UUID id) {
        PartyBrief party = parties.briefs(List.of(id)).get(id);
        if (party == null || party.archived()) {
            throw ApiProblem.conflict(Orders.ARCHIVED);
        }
    }

    /**
     * Orders match a search (an escaped, lowered LIKE pattern) by their number or by their party's name (D14).
     * {@code partyField} is the order's supplierId or customerId attribute.
     */
    <T> Specification<T> numberOrPartyName(String pattern, String partyField) {
        Set<UUID> named = parties.idsMatching(pattern, NAME_MATCHES);
        return (root, cq, cb) -> {
            Predicate byNumber = cb.like(cb.lower(root.get("number")), pattern, '\\');
            return named.isEmpty() ? byNumber : cb.or(byNumber, root.get(partyField).in(named));
        };
    }

    Map<UUID, PartyRef> refs(Collection<UUID> ids) {
        return parties.briefs(ids).values().stream()
                .collect(Collectors.toMap(PartyBrief::id, p -> new PartyRef(p.id(), p.name())));
    }
}
