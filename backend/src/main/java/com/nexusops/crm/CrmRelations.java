package com.nexusops.crm;

import com.nexusops.collaboration.SubjectKey;
import com.nexusops.collaboration.SubjectRelations;
import com.nexusops.crm.domain.Lead;
import com.nexusops.crm.domain.LeadRepository;
import com.nexusops.crm.domain.Opportunity;
import com.nexusops.crm.domain.OpportunityRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/** D10: a party's timeline includes its opportunities (as account or contact) and the leads converted into it;
 * an opportunity's includes its source lead. */
@Component
class CrmRelations implements SubjectRelations {

    private static final int LIMIT = 100;

    private final OpportunityRepository opportunities;
    private final LeadRepository leads;

    CrmRelations(OpportunityRepository opportunities, LeadRepository leads) {
        this.opportunities = opportunities;
        this.leads = leads;
    }

    @Override
    public List<SubjectKey> related(String type, UUID id) {
        List<SubjectKey> keys = new ArrayList<>();
        if (type.equals("PARTY")) {
            Specification<Opportunity> deals = (root, cq, cb) -> cb.or(cb.equal(root.get("accountId"), id),
                    cb.equal(root.get("contactId"), id));
            opportunities.findAll(deals, PageRequest.of(0, LIMIT))
                    .forEach(o -> keys.add(new SubjectKey(OpportunitySubjects.TYPE, o.getId())));
            Specification<Lead> converted = (root, cq, cb) -> cb.or(cb.equal(root.get("convertedPersonId"), id),
                    cb.equal(root.get("convertedOrganizationId"), id));
            leads.findAll(converted, PageRequest.of(0, LIMIT))
                    .forEach(l -> keys.add(new SubjectKey(LeadSubjects.TYPE, l.getId())));
        } else if (type.equals(OpportunitySubjects.TYPE)) {
            opportunities.findById(id).map(Opportunity::getLeadId)
                    .ifPresent(lead -> keys.add(new SubjectKey(LeadSubjects.TYPE, lead)));
        }
        return keys;
    }
}
