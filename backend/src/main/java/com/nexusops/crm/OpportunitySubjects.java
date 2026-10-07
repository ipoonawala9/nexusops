package com.nexusops.crm;

import com.nexusops.collaboration.SubjectRef;
import com.nexusops.collaboration.SubjectResolver;
import com.nexusops.crm.domain.Opportunity;
import com.nexusops.crm.domain.OpportunityRepository;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Opportunities as collaboration subjects (type OPPORTUNITY, D9). Closed deals still take notes. */
@Component
class OpportunitySubjects implements SubjectResolver {

    static final String TYPE = "OPPORTUNITY";

    private final OpportunityRepository opportunities;

    OpportunitySubjects(OpportunityRepository opportunities) {
        this.opportunities = opportunities;
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

    static SubjectRef ref(Opportunity o) {
        return new SubjectRef(TYPE, o.getId(), o.getName(), false);
    }
}
