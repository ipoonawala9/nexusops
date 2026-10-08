package com.nexusops.inventory;

import com.nexusops.directory.DirectoryPermissions;
import com.nexusops.directory.PartyBrief;
import com.nexusops.directory.PartyRef;
import com.nexusops.directory.PartyService;
import com.nexusops.shared.security.CurrentAuthorities;
import com.nexusops.shared.web.ApiProblem;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Suppliers and customers as orders see them. */
@Component
class OrderParties {

    static final String UNKNOWN = "Choose a person or organization in this workspace.";

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

    Map<UUID, PartyRef> refs(Collection<UUID> ids) {
        return parties.briefs(ids).values().stream()
                .collect(Collectors.toMap(PartyBrief::id, p -> new PartyRef(p.id(), p.name())));
    }
}
