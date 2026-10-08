package com.nexusops.crm;

import com.nexusops.collaboration.SearchHit;
import com.nexusops.collaboration.SubjectRef;
import com.nexusops.collaboration.SubjectResolver;
import com.nexusops.crm.domain.Lead;
import com.nexusops.crm.domain.LeadRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    @Override
    public List<SearchHit> search(String pattern, int limit) {
        return leads.findAll(LeadService.matching(pattern), PageRequest.of(0, limit, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))))
                .stream().map(l -> new SearchHit(TYPE, l.getId(), l.getName(),
                        l.getName().equals(l.getCompanyName()) ? l.getEmail() : l.getCompanyName(),
                        l.getStatus() == LeadStatus.CONVERTED))
                .toList();
    }

    static SubjectRef ref(Lead lead) {
        return new SubjectRef(TYPE, lead.getId(), lead.getName(), lead.getStatus() == LeadStatus.CONVERTED);
    }
}
