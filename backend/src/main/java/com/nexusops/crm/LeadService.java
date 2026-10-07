package com.nexusops.crm;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.collaboration.MemberRef;
import com.nexusops.crm.domain.Lead;
import com.nexusops.crm.domain.LeadDetails;
import com.nexusops.crm.domain.LeadRepository;
import com.nexusops.directory.PartyBrief;
import com.nexusops.directory.PartyRef;
import com.nexusops.directory.PartyService;
import com.nexusops.identity.Members;
import com.nexusops.shared.Emails;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.shared.web.PageResponse;
import com.nexusops.shared.web.Paging;
import com.nexusops.tenancy.TenantDirectory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Leads (D2, D3). Conversion (D4) and import (D11) live in their own services and reuse validate/find. */
@Service
public class LeadService {

    static final String NOT_FOUND = "Record not found.";
    static final String STALE = "This record was changed by someone else. Reload and try again.";
    static final String NOT_A_MEMBER = "Choose an active member of this workspace.";
    static final String NAME_REQUIRED = "Enter a name or a company.";
    private static final Set<LeadStatus> OPEN = Set.of(LeadStatus.NEW, LeadStatus.CONTACTED, LeadStatus.QUALIFIED);

    private final LeadRepository leads;
    private final Members members;
    private final PartyService parties;
    private final TenantDirectory tenants;
    private final AuditService audit;

    LeadService(LeadRepository leads, Members members, PartyService parties, TenantDirectory tenants,
            AuditService audit) {
        this.leads = leads;
        this.members = members;
        this.parties = parties;
        this.tenants = tenants;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public LeadView get(UUID id) {
        return view(find(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<LeadView> list(LeadQuery query, Integer page, Integer size) {
        TenantContext.requireTenantId();
        Set<LeadStatus> statuses = parseStatuses(query.status());
        Specification<Lead> spec = (root, cq, cb) -> root.get("status").in(statuses);
        String owner = query.owner() == null ? "" : query.owner().strip();
        if (owner.equals("me")) {
            UUID me = currentUser();
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("ownerId"), me));
        } else if (owner.equals("unassigned")) {
            spec = spec.and((root, cq, cb) -> cb.isNull(root.get("ownerId")));
        } else if (!owner.isEmpty()) {
            UUID id = parseUuid(owner, "owner");
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("ownerId"), id));
        }
        if (query.source() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("source"), query.source()));
        }
        if (query.partyId() != null) {
            UUID party = query.partyId();
            spec = spec.and((root, cq, cb) -> cb.or(cb.equal(root.get("convertedPersonId"), party),
                    cb.equal(root.get("convertedOrganizationId"), party)));
        }
        String q = Text.optional(query.q(), 100, "q");
        if (q != null) {
            String like = Text.containsPattern(q);
            spec = spec.and((root, cq, cb) -> cb.or(cb.like(cb.lower(root.get("firstName")), like, '\\'),
                    cb.like(cb.lower(root.get("lastName")), like, '\\'),
                    cb.like(cb.lower(root.get("companyName")), like, '\\'), cb.like(root.get("email"), like, '\\')));
        }
        Page<Lead> result = leads.findAll(spec,
                Paging.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))));
        return new PageResponse<>(views(result.getContent()), result.getNumber(), result.getSize(),
                result.getTotalElements());
    }

    @Transactional
    public LeadView create(LeadCommand command) {
        TenantContext.requireTenantId();
        LeadDetails details = validate(command, null);
        if (details.ownerId() == null) {
            details = withOwner(details, currentUser());
        }
        Lead lead = new Lead(Ids.newId(), details, currentUser());
        leads.saveAndFlush(lead);
        audit.record(AuditEntry.of("LeadCreated", "Lead", lead.getId()).withAfter(snapshot(lead)));
        return view(lead);
    }

    @Transactional
    public LeadView update(UUID id, LeadCommand command, Long version) {
        Lead lead = find(id);
        checkVersion(lead, version);
        if (lead.getStatus() == LeadStatus.CONVERTED) {
            throw ApiProblem.conflict(LeadStatus.CONVERTED_FINAL);
        }
        LeadDetails details = validate(command, lead);
        Map<String, Object> before = snapshot(lead);
        lead.apply(details);
        leads.flush();
        audit.record(AuditEntry.of("LeadUpdated", "Lead", id).withBefore(before).withAfter(snapshot(lead)));
        return view(lead);
    }

    @Transactional
    public LeadView changeStatus(UUID id, LeadStatus status, String reason, Long version) {
        Lead lead = find(id);
        checkVersion(lead, version);
        if (status == null) {
            throw ApiProblem.badRequestField("status", "Choose a status.");
        }
        LeadStatus.check(lead.getStatus(), status);
        String why = status == LeadStatus.DISQUALIFIED ? Text.required(reason, 500, "reason") : null;
        if (lead.getStatus() != status || !Objects.equals(lead.getDisqualifyReason(), why)) {
            LeadStatus before = lead.getStatus();
            lead.changeStatus(status, why, Instant.now());
            leads.flush();
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", status.name());
            if (why != null) {
                after.put("reason", why);
            }
            audit.record(AuditEntry.of("LeadStatusChanged", "Lead", id).withBefore(Map.of("status", before.name()))
                    .withAfter(after));
        }
        return view(lead);
    }

    Lead find(UUID id) {
        TenantContext.requireTenantId();
        return leads.findById(id).orElseThrow(() -> ApiProblem.notFound(NOT_FOUND));
    }

    /** For conversion: the lead exists, the version matches, and it is open. */
    Lead requireConvertible(UUID id, Long version) {
        Lead lead = find(id);
        checkVersion(lead, version);
        if (lead.getStatus() == LeadStatus.CONVERTED) {
            throw ApiProblem.conflict(LeadStatus.CONVERTED_FINAL);
        }
        if (lead.getStatus() == LeadStatus.DISQUALIFIED) {
            throw ApiProblem.conflict("Reopen the lead before converting it.");
        }
        return lead;
    }

    /** {@code current} is null on create (and import); an unchanged owner isn't re-checked. */
    LeadDetails validate(LeadCommand command, Lead current) {
        String firstName = Text.optional(command.firstName(), 80, "firstName");
        String lastName = Text.optional(command.lastName(), 80, "lastName");
        String company = Text.optional(command.companyName(), 200, "companyName");
        if (firstName == null && lastName == null && company == null) {
            throw ApiProblem.badRequestField("lastName", NAME_REQUIRED);
        }
        String jobTitle = Text.optional(command.jobTitle(), 100, "jobTitle");
        String email = command.email() == null || command.email().isBlank() ? null : Emails.normalize(command.email());
        String phone = Text.optional(command.phone(), 40, "phone");
        if (phone != null) {
            phone = phone.replaceAll("\\s+", " ");
        }
        LeadSource source = command.source() == null ? LeadSource.OTHER : command.source();
        UUID ownerId = command.ownerId();
        if (ownerId != null && (current == null || !ownerId.equals(current.getOwnerId()))
                && members.findActive(ownerId).isEmpty()) {
            throw ApiProblem.badRequestField("ownerId", NOT_A_MEMBER);
        }
        BigDecimal value = Money.amount(command.estimatedValue(), "estimatedValue");
        String currency = value == null ? null : Money.currency(command.currency(), "currency", tenants);
        String description = Text.optional(command.description(), 5000, "description");
        return new LeadDetails(firstName, lastName, company, jobTitle, email, phone, source, ownerId, value, currency,
                description);
    }

    LeadView view(Lead lead) {
        return views(List.of(lead)).getFirst();
    }

    private List<LeadView> views(List<Lead> page) {
        Map<UUID, Members.Member> people = members.findAll(page.stream()
                .flatMap(l -> Stream.of(l.getOwnerId(), l.getCreatedBy())).filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        Map<UUID, PartyBrief> partyNames = parties.briefs(page.stream()
                .flatMap(l -> Stream.of(l.getConvertedPersonId(), l.getConvertedOrganizationId()))
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return page.stream().map(l -> new LeadView(l.getId(), l.getName(), l.getFirstName(), l.getLastName(),
                l.getCompanyName(), l.getJobTitle(), l.getEmail(), l.getPhone(), l.getSource(), l.getStatus(),
                member(people, l.getOwnerId()), l.getEstimatedValue(), l.getCurrency(), l.getDescription(),
                l.getDisqualifyReason(), l.getDisqualifiedAt(), l.getConvertedAt(),
                party(partyNames, l.getConvertedPersonId()), party(partyNames, l.getConvertedOrganizationId()),
                l.getConvertedOpportunityId(), member(people, l.getCreatedBy()), l.getCreatedAt(), l.getUpdatedAt(),
                l.getVersion())).toList();
    }

    static Map<String, Object> snapshot(Lead lead) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", lead.getName());
        values.put("status", lead.getStatus().name());
        values.put("source", lead.getSource().name());
        if (lead.getCompanyName() != null) values.put("companyName", lead.getCompanyName());
        if (lead.getOwnerId() != null) values.put("ownerId", lead.getOwnerId().toString());
        if (lead.getEstimatedValue() != null) {
            values.put("estimatedValue", lead.getEstimatedValue().toPlainString());
            values.put("currency", lead.getCurrency());
        }
        return values;
    }

    private static LeadDetails withOwner(LeadDetails d, UUID owner) {
        return new LeadDetails(d.firstName(), d.lastName(), d.companyName(), d.jobTitle(), d.email(), d.phone(),
                d.source(), owner, d.estimatedValue(), d.currency(), d.description());
    }

    private static void checkVersion(Lead lead, Long version) {
        if (version == null) {
            throw ApiProblem.badRequestField("version", "Reload the record and try again.");
        }
        if (lead.getVersion() != version) {
            throw ApiProblem.conflict(STALE);
        }
    }

    private static MemberRef member(Map<UUID, Members.Member> people, UUID id) {
        Members.Member m = id == null ? null : people.get(id);
        return m == null ? null : new MemberRef(m.id(), m.name());
    }

    private static PartyRef party(Map<UUID, PartyBrief> parties, UUID id) {
        PartyBrief p = id == null ? null : parties.get(id);
        return p == null ? null : new PartyRef(p.id(), p.name());
    }

    static UUID currentUser() {
        return TenantContext.userId().orElseThrow(() -> new IllegalStateException("No user bound"));
    }

    static UUID parseUuid(String raw, String field) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException invalid) {
            throw ApiProblem.badRequestField(field, "Use me, unassigned or a member id.");
        }
    }

    private static Set<LeadStatus> parseStatuses(String raw) {
        if (raw == null || raw.isBlank()) {
            return OPEN;
        }
        try {
            return Arrays.stream(raw.split(",")).map(s -> LeadStatus.valueOf(s.strip().toUpperCase(Locale.ROOT)))
                    .collect(Collectors.toSet());
        } catch (IllegalArgumentException invalid) {
            throw ApiProblem.badRequestField("status", "Use NEW, CONTACTED, QUALIFIED, DISQUALIFIED or CONVERTED.");
        }
    }
}
