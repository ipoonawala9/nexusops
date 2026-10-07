package com.nexusops.crm;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.crm.ConversionCommand.NewOpportunity;
import com.nexusops.crm.ConversionCommand.OrganizationChoice;
import com.nexusops.crm.ConversionCommand.PersonChoice;
import com.nexusops.crm.domain.Lead;
import com.nexusops.crm.domain.LeadRepository;
import com.nexusops.directory.DirectoryPermissions;
import com.nexusops.directory.OrganizationCommand;
import com.nexusops.directory.PartyBrief;
import com.nexusops.directory.PartyKind;
import com.nexusops.directory.PartyService;
import com.nexusops.directory.PersonCommand;
import com.nexusops.identity.Members;
import com.nexusops.shared.security.CurrentAuthorities;
import com.nexusops.shared.web.ApiProblem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Converts an open lead (D4) in one transaction: organization, then person (placed in it), the CUSTOMER role on the
 * account, an optional opportunity, then the lead itself. Any failure — a duplicate included — writes nothing.
 */
@Service
public class LeadConversionService {

    static final String FORBIDDEN = "You do not have permission to perform this action.";
    static final String NOTHING_CHOSEN = "Choose or create a person or an organization.";

    private final LeadService leads;
    private final LeadRepository leadRows;
    private final PartyService parties;
    private final OpportunityService opportunities;
    private final Members members;
    private final AuditService audit;

    LeadConversionService(LeadService leads, LeadRepository leadRows, PartyService parties,
            OpportunityService opportunities, Members members, AuditService audit) {
        this.leads = leads;
        this.leadRows = leadRows;
        this.parties = parties;
        this.opportunities = opportunities;
        this.members = members;
        this.audit = audit;
    }

    @Transactional
    public LeadView convert(UUID id, ConversionCommand command, Long version) {
        Lead lead = leads.requireConvertible(id, version);
        PersonChoice person = command.person();
        OrganizationChoice organization = command.organization();
        if (person == null && organization == null) {
            throw ApiProblem.badRequestField("person", NOTHING_CHOSEN);
        }
        boolean creates = (person != null && person.existingId() == null)
                || (organization != null && organization.existingId() == null);
        boolean links = (person != null && person.existingId() != null)
                || (organization != null && organization.existingId() != null);
        require(!creates || CurrentAuthorities.has(DirectoryPermissions.PARTY_MANAGE));
        require(!links || CurrentAuthorities.has(DirectoryPermissions.PARTY_READ));
        require(command.opportunity() == null || CurrentAuthorities.has(CrmPermissions.OPPORTUNITY_MANAGE));

        UUID organizationId = organization == null ? null
                : organization.existingId() != null
                        ? linked(organization.existingId(), PartyKind.ORGANIZATION, "organization.existingId")
                        : createOrganization(organization);
        UUID personId = person == null ? null
                : person.existingId() != null ? linked(person.existingId(), PartyKind.PERSON, "person.existingId")
                        : createPerson(person, organizationId);
        UUID account = organizationId != null ? organizationId : personId;
        parties.ensureCustomer(account);
        UUID opportunityId = command.opportunity() == null ? null
                : openOpportunity(lead, command.opportunity(), account, personId);

        lead.markConverted(personId, organizationId, opportunityId, Instant.now());
        leadRows.flush();
        Map<String, Object> after = new LinkedHashMap<>();
        if (personId != null) after.put("personId", personId.toString());
        if (organizationId != null) after.put("organizationId", organizationId.toString());
        if (opportunityId != null) after.put("opportunityId", opportunityId.toString());
        audit.record(AuditEntry.of("LeadConverted", "Lead", id).withAfter(after));
        return leads.view(lead);
    }

    private UUID linked(UUID id, PartyKind kind, String field) {
        PartyBrief party = parties.briefs(List.of(id)).get(id);
        if (party == null || party.kind() != kind) {
            throw ApiProblem.badRequestField(field,
                    kind == PartyKind.PERSON ? "Choose a person in this workspace." : "Choose an organization in this workspace.");
        }
        if (party.archived()) {
            throw ApiProblem.conflict("This record is archived.");
        }
        return id;
    }

    private UUID createOrganization(OrganizationChoice c) {
        try {
            return parties.createOrganization(new OrganizationCommand(c.name(), c.domain(), c.website(), c.email(),
                    c.phone(), c.duplicateReason())).id();
        } catch (ApiProblem problem) {
            throw relabel(problem, "organization");
        }
    }

    private UUID createPerson(PersonChoice c, UUID organizationId) {
        try {
            return parties.createPerson(new PersonCommand(c.firstName(), c.lastName(), c.jobTitle(), organizationId,
                    c.email(), c.phone(), c.duplicateReason())).id();
        } catch (ApiProblem problem) {
            throw relabel(problem, "person");
        }
    }

    private UUID openOpportunity(Lead lead, NewOpportunity o, UUID account, UUID contact) {
        String name = o.name() == null || o.name().isBlank() ? lead.getName() : o.name();
        BigDecimal amount = o.amount() != null ? o.amount() : lead.getEstimatedValue();
        String currency = o.amount() != null ? o.currency() : lead.getCurrency();
        UUID owner = lead.getOwnerId() != null && members.findActive(lead.getOwnerId()).isPresent()
                ? lead.getOwnerId() : null;
        try {
            return opportunities.open(new OpportunityCommand(name, account, contact, o.stageId(), amount, currency,
                    o.expectedCloseOn(), owner, null), lead.getId()).getId();
        } catch (ApiProblem problem) {
            throw problem.prefixed("opportunity.");
        }
    }

    /** Duplicates keep their candidates and say which party they are about; field errors get the prefix. */
    private static ApiProblem relabel(ApiProblem problem, String party) {
        return problem.properties().containsKey("duplicates") ? problem.withProperty("party", party)
                : problem.prefixed(party + ".");
    }

    private static void require(boolean allowed) {
        if (!allowed) {
            throw ApiProblem.forbidden(FORBIDDEN);
        }
    }
}
