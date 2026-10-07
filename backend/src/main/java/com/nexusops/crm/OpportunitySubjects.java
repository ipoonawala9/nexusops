package com.nexusops.crm;

import com.nexusops.collaboration.SearchHit;
import com.nexusops.collaboration.SubjectRef;
import com.nexusops.collaboration.SubjectResolver;
import com.nexusops.crm.domain.Opportunity;
import com.nexusops.crm.domain.OpportunityRepository;
import com.nexusops.directory.PartyBrief;
import com.nexusops.directory.PartyService;
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

/** Opportunities as collaboration subjects (type OPPORTUNITY, D9). Closed deals still take notes. */
@Component
class OpportunitySubjects implements SubjectResolver {

    static final String TYPE = "OPPORTUNITY";

    private final OpportunityRepository opportunities;
    private final PartyService parties;

    OpportunitySubjects(OpportunityRepository opportunities, PartyService parties) {
        this.opportunities = opportunities;
        this.parties = parties;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String readPermission() {
        return CrmPermissions.OPPORTUNITY_READ;
    }

    @Override
    public Optional<SubjectRef> find(UUID id) {
        return opportunities.findById(id).map(OpportunitySubjects::ref);
    }

    @Override
    public Map<UUID, SubjectRef> findAll(Collection<UUID> ids) {
        return opportunities.findAllById(ids).stream().collect(Collectors.toMap(Opportunity::getId, OpportunitySubjects::ref));
    }

    @Override
    public List<SearchHit> search(String pattern, int limit) {
        Specification<Opportunity> spec = (root, cq, cb) -> cb.like(cb.lower(root.get("name")), pattern, '\\');
        List<Opportunity> found = opportunities.findAll(spec,
                PageRequest.of(0, limit, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")))).getContent();
        Map<UUID, PartyBrief> accounts = parties.briefs(found.stream().map(Opportunity::getAccountId)
                .collect(Collectors.toSet()));
        return found.stream().map(o -> new SearchHit(TYPE, o.getId(), o.getName(),
                accounts.containsKey(o.getAccountId()) ? accounts.get(o.getAccountId()).name() : null, false)).toList();
    }

    static SubjectRef ref(Opportunity o) {
        return new SubjectRef(TYPE, o.getId(), o.getName(), false);
    }
}
