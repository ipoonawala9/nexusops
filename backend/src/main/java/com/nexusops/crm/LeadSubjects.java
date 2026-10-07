package com.nexusops.crm;

import com.nexusops.collaboration.SubjectRef;
import com.nexusops.collaboration.SubjectResolver;
import com.nexusops.crm.domain.Lead;
import com.nexusops.crm.domain.LeadRepository;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Leads as collaboration subjects (type LEAD, D9). A converted lead is frozen: it reports archived. */
@Component
class LeadSubjects implements SubjectResolver {

    static final String TYPE = "LEAD";

    private final LeadRepository leads;

    LeadSubjects(LeadRepository leads) {
        this.leads = leads;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String readPermission() {
        return CrmPermissions.LEAD_READ;
    }

    @Override
    public Optional<SubjectRef> find(UUID id) {
        return leads.findById(id).map(LeadSubjects::ref);
    }

    @Override
    public Map<UUID, SubjectRef> findAll(Collection<UUID> ids) {
        return leads.findAllById(ids).stream().collect(Collectors.toMap(Lead::getId, LeadSubjects::ref));
    }

    static SubjectRef ref(Lead lead) {
        return new SubjectRef(TYPE, lead.getId(), lead.getName(), lead.getStatus() == LeadStatus.CONVERTED);
    }
}
