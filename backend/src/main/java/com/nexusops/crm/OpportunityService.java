package com.nexusops.crm;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.collaboration.MemberRef;
import com.nexusops.crm.BoardView.BoardColumn;
import com.nexusops.crm.domain.Opportunity;
import com.nexusops.crm.domain.OpportunityDetails;
import com.nexusops.crm.domain.OpportunityRepository;
import com.nexusops.crm.domain.PipelineStage;
import com.nexusops.directory.DirectoryPermissions;
import com.nexusops.directory.PartyBrief;
import com.nexusops.directory.PartyKind;
import com.nexusops.directory.PartyRef;
import com.nexusops.directory.PartyService;
import com.nexusops.identity.Members;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.security.CurrentAuthorities;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.shared.web.PageResponse;
import com.nexusops.shared.web.Paging;
import com.nexusops.tenancy.TenantDirectory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Opportunities (D6) and the pipeline board. Won deals make their account a customer (D7). */
@Service
public class OpportunityService {

    static final String NOT_FOUND = "Record not found.";
    static final String STALE = "This record was changed by someone else. Reload and try again.";
    static final String FORBIDDEN = "You do not have permission to perform this action.";
    static final String NOT_A_MEMBER = "Choose an active member of this workspace.";
    static final String UNKNOWN_PARTY = "Choose a person or organization in this workspace.";
    static final String NOT_A_PERSON = "Choose a person.";
    static final String ARCHIVED = "This record is archived.";
    static final String OPEN_STAGE_ONLY = "Create opportunities in an open stage, then move them.";
    private static final int BOARD_LIMIT = 50;
    private static final Duration RECENTLY_CLOSED = Duration.ofDays(30);
    private static final Sort CARD_ORDER = Sort.by(Sort.Order.asc("expectedCloseOn").nullsLast(),
            Sort.Order.desc("createdAt"), Sort.Order.asc("id"));

    private final OpportunityRepository opportunities;
    private final PipelineService pipeline;
    private final PartyService parties;
    private final Members members;
    private final TenantDirectory tenants;
    private final AuditService audit;
    private final NamedParameterJdbcTemplate jdbc;

    OpportunityService(OpportunityRepository opportunities, PipelineService pipeline, PartyService parties,
            Members members, TenantDirectory tenants, AuditService audit, NamedParameterJdbcTemplate jdbc) {
        this.opportunities = opportunities;
        this.pipeline = pipeline;
        this.parties = parties;
        this.members = members;
        this.tenants = tenants;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public OpportunityView get(UUID id) {
        return views(List.of(find(id))).getFirst();
    }

    @Transactional(readOnly = true)
    public PageResponse<OpportunityView> list(OpportunityQuery query, Integer page, Integer size) {
        TenantContext.requireTenantId();
        Specification<Opportunity> spec = ownerFilter(query.owner());
        if (query.status() != null) {
            Set<UUID> ofKind = pipeline.ordered().stream()
                    .filter(s -> s.getKind().name().equals(query.status().name()))
                    .map(PipelineStage::getId).collect(Collectors.toSet());
            spec = spec.and((root, cq, cb) -> root.get("stageId").in(ofKind));
        }
        if (query.stageId() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("stageId"), query.stageId()));
        }
        if (query.accountId() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("accountId"), query.accountId()));
        }
        if (query.contactId() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("contactId"), query.contactId()));
        }
        String q = Text.optional(query.q(), 100, "q");
        if (q != null) {
            String like = Text.containsPattern(q);
            spec = spec.and((root, cq, cb) -> cb.like(cb.lower(root.get("name")), like, '\\'));
        }
        Page<Opportunity> result = opportunities.findAll(spec,
                Paging.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))));
        return new PageResponse<>(views(result.getContent()), result.getNumber(), result.getSize(),
                result.getTotalElements());
    }

    @Transactional
    public OpportunityView create(OpportunityCommand command) {
        return get(open(command, null).getId());
    }

    /** Creates an opportunity in an open stage; {@code leadId} when converting a lead (Task 4). */
    @Transactional
    Opportunity open(OpportunityCommand command, UUID leadId) {
        TenantContext.requireTenantId();
        OpportunityDetails details = validate(command, null);
        if (details.ownerId() == null) {
            details = withOwner(details, LeadService.currentUser());
        }
        PipelineStage stage = command.stageId() == null ? pipeline.firstOpen()
                : pipeline.require(command.stageId(), "stageId");
        if (stage.getKind() != StageKind.OPEN) {
            throw ApiProblem.badRequestField("stageId", OPEN_STAGE_ONLY);
        }
        Opportunity opportunity = new Opportunity(Ids.newId(), details, stage.getId(), leadId, LeadService.currentUser());
        opportunities.saveAndFlush(opportunity);
        audit.record(AuditEntry.of("OpportunityCreated", "Opportunity", opportunity.getId())
                .withAfter(snapshot(opportunity, stage)));
        return opportunity;
    }

    @Transactional
    public OpportunityView update(UUID id, OpportunityCommand command, Long version) {
        Opportunity opportunity = find(id);
        checkVersion(opportunity, version);
        OpportunityDetails details = validate(command, opportunity);
        PipelineStage stage = pipeline.require(opportunity.getStageId(), "stageId");
        Map<String, Object> before = snapshot(opportunity, stage);
        opportunity.apply(details);
        opportunities.flush();
        audit.record(AuditEntry.of("OpportunityUpdated", "Opportunity", id).withBefore(before)
                .withAfter(snapshot(opportunity, stage)));
        return get(id);
    }

    @Transactional
    public OpportunityView moveStage(UUID id, UUID stageId, String lostReason, Long version) {
        Opportunity opportunity = find(id);
        checkVersion(opportunity, version);
        PipelineStage target = pipeline.require(stageId, "stageId");
        if (target.getId().equals(opportunity.getStageId())) {
            return get(id);
        }
        PipelineStage current = pipeline.require(opportunity.getStageId(), "stageId");
        String reason = target.getKind() == StageKind.LOST ? Text.required(lostReason, 500, "lostReason") : null;
        Instant closed = target.getKind() == StageKind.OPEN ? null
                : current.getKind() == target.getKind() ? opportunity.getClosedAt() : Instant.now();
        Map<String, Object> before = Map.of("stageId", current.getId().toString(), "stage", current.getName());
        opportunity.moveTo(target.getId(), closed, reason);
        opportunities.flush();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("stageId", target.getId().toString());
        after.put("stage", target.getName());
        if (reason != null) {
            after.put("lostReason", reason);
        }
        audit.record(AuditEntry.of("OpportunityStageChanged", "Opportunity", id).withBefore(before).withAfter(after));
        if (target.getKind() == StageKind.WON) {
            parties.ensureCustomer(opportunity.getAccountId());
        }
        return get(id);
    }

    @Transactional(readOnly = true)
    public BoardView board(String owner) {
        UUID tenant = TenantContext.requireTenantId();
        UUID ownerId = boardOwner(owner);
        Instant since = Instant.now().minus(RECENTLY_CLOSED);
        Map<UUID, List<Object[]>> sums = stageSums(tenant, ownerId, since);
        List<BoardColumn> columns = new ArrayList<>();
        for (PipelineStage stage : pipeline.ordered()) {
            Specification<Opportunity> spec = (root, cq, cb) -> cb.equal(root.get("stageId"), stage.getId());
            if (ownerId != null) {
                spec = spec.and((root, cq, cb) -> cb.equal(root.get("ownerId"), ownerId));
            }
            if (stage.getKind() != StageKind.OPEN) {
                spec = spec.and((root, cq, cb) -> cb.greaterThanOrEqualTo(root.get("closedAt"), since));
            }
            List<Opportunity> cards = opportunities.findAll(spec, PageRequest.of(0, BOARD_LIMIT, CARD_ORDER)).getContent();
            List<Object[]> rows = sums.getOrDefault(stage.getId(), List.of());
            long count = rows.stream().mapToLong(r -> (Long) r[1]).sum();
            List<MoneyTotal> totals = rows.stream().filter(r -> r[0] != null)
                    .map(r -> new MoneyTotal((String) r[0], (BigDecimal) r[2])).toList();
            List<MoneyTotal> weighted = totals.stream().map(t -> new MoneyTotal(t.currency(),
                    t.amount().multiply(BigDecimal.valueOf(stage.getProbability()))
                            .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP))).toList();
            columns.add(new BoardColumn(PipelineService.view(stage), count, totals, weighted, summaries(cards)));
        }
        return new BoardView(columns);
    }

    Opportunity find(UUID id) {
        TenantContext.requireTenantId();
        return opportunities.findById(id).orElseThrow(() -> ApiProblem.notFound(NOT_FOUND));
    }

    List<OpportunitySummary> summaries(List<Opportunity> list) {
        Map<UUID, PartyBrief> partyNames = parties.briefs(list.stream().map(Opportunity::getAccountId)
                .collect(Collectors.toSet()));
        Map<UUID, Members.Member> people = members.findAll(list.stream().map(Opportunity::getOwnerId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<UUID, PipelineStage> stages = stagesById();
        return list.stream().map(o -> new OpportunitySummary(o.getId(), o.getName(), party(partyNames, o.getAccountId()),
                PipelineService.ref(stages.get(o.getStageId())), o.getAmount(), o.getCurrency(), o.getExpectedCloseOn(),
                member(people, o.getOwnerId()), o.getVersion())).toList();
    }

    /** Rows of [currency, count, sum] per stage; open stages count everything, closed ones since {@code since}. */
    private Map<UUID, List<Object[]>> stageSums(UUID tenant, UUID ownerId, Instant since) {
        String sql = """
                select o.stage_id, o.currency, count(*) as n, sum(o.amount) as total
                from opportunities o join pipeline_stages s on s.id = o.stage_id and s.tenant_id = o.tenant_id
                where o.tenant_id = :tenant and (s.kind = 'OPEN' or o.closed_at >= :since)
                """ + (ownerId == null ? "" : " and o.owner_id = :owner") + " group by o.stage_id, o.currency";
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", tenant)
                .addValue("since", java.sql.Timestamp.from(since)).addValue("owner", ownerId);
        Map<UUID, List<Object[]>> result = new HashMap<>();
        jdbc.query(sql, params, rs -> {
            result.computeIfAbsent(rs.getObject("stage_id", UUID.class), k -> new ArrayList<>())
                    .add(new Object[] {rs.getString("currency"), rs.getLong("n"), rs.getBigDecimal("total")});
        });
        return result;
    }

    /** {@code current} null on create. Unchanged parties and owners aren't re-checked (they may since be archived or
     * disabled, which mustn't block editing the rest). Referencing a party needs directory.party.read. */
    private OpportunityDetails validate(OpportunityCommand command, Opportunity current) {
        String name = Text.required(command.name(), 200, "name").replaceAll("\\s+", " ");
        UUID accountId = command.accountId();
        if (accountId == null) {
            throw ApiProblem.badRequestField("accountId", UNKNOWN_PARTY);
        }
        if (current == null || !accountId.equals(current.getAccountId())) {
            requireParty(accountId, "accountId", false);
        }
        UUID contactId = command.contactId();
        if (contactId != null && (current == null || !contactId.equals(current.getContactId()))) {
            requireParty(contactId, "contactId", true);
        }
        BigDecimal amount = Money.amount(command.amount(), "amount");
        String currency = amount == null ? null : Money.currency(command.currency(), "currency", tenants);
        UUID ownerId = command.ownerId();
        if (ownerId != null && (current == null || !ownerId.equals(current.getOwnerId()))
                && members.findActive(ownerId).isEmpty()) {
            throw ApiProblem.badRequestField("ownerId", NOT_A_MEMBER);
        }
        String description = Text.optional(command.description(), 5000, "description");
        return new OpportunityDetails(name, accountId, contactId, amount, currency, command.expectedCloseOn(), ownerId,
                description);
    }

    private void requireParty(UUID id, String field, boolean person) {
        if (!CurrentAuthorities.has(DirectoryPermissions.PARTY_READ)) {
            throw ApiProblem.forbidden(FORBIDDEN);
        }
        PartyBrief party = parties.briefs(List.of(id)).get(id);
        if (party == null) {
            throw ApiProblem.badRequestField(field, UNKNOWN_PARTY);
        }
        if (person && party.kind() != PartyKind.PERSON) {
            throw ApiProblem.badRequestField(field, NOT_A_PERSON);
        }
        if (party.archived()) {
            throw ApiProblem.conflict(ARCHIVED);
        }
    }

    private List<OpportunityView> views(List<Opportunity> page) {
        Map<UUID, PartyBrief> partyNames = parties.briefs(page.stream()
                .flatMap(o -> Stream.of(o.getAccountId(), o.getContactId())).filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        Map<UUID, Members.Member> people = members.findAll(page.stream()
                .flatMap(o -> Stream.of(o.getOwnerId(), o.getCreatedBy())).filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        Map<UUID, PipelineStage> stages = stagesById();
        return page.stream().map(o -> {
            PipelineStage stage = stages.get(o.getStageId());
            return new OpportunityView(o.getId(), o.getName(), party(partyNames, o.getAccountId()),
                    party(partyNames, o.getContactId()), PipelineService.ref(stage),
                    OpportunityStatus.valueOf(stage.getKind().name()), o.getAmount(), o.getCurrency(),
                    o.getExpectedCloseOn(), member(people, o.getOwnerId()), o.getLeadId(), o.getDescription(),
                    o.getLostReason(), o.getClosedAt(), member(people, o.getCreatedBy()), o.getCreatedAt(),
                    o.getUpdatedAt(), o.getVersion());
        }).toList();
    }

    private Map<UUID, PipelineStage> stagesById() {
        return pipeline.ordered().stream().collect(Collectors.toMap(PipelineStage::getId, s -> s));
    }

    private Specification<Opportunity> ownerFilter(String raw) {
        String owner = raw == null ? "" : raw.strip();
        if (owner.equals("me")) {
            UUID me = LeadService.currentUser();
            return (root, cq, cb) -> cb.equal(root.get("ownerId"), me);
        }
        if (owner.equals("unassigned")) {
            return (root, cq, cb) -> cb.isNull(root.get("ownerId"));
        }
        if (!owner.isEmpty()) {
            UUID id = LeadService.parseUuid(owner, "owner");
            return (root, cq, cb) -> cb.equal(root.get("ownerId"), id);
        }
        return (root, cq, cb) -> cb.conjunction();
    }

    private static UUID boardOwner(String raw) {
        String owner = raw == null ? "" : raw.strip();
        if (owner.isEmpty() || owner.equals("all")) {
            return null;
        }
        if (owner.equals("me")) {
            return LeadService.currentUser();
        }
        throw ApiProblem.badRequestField("owner", "Use me or all.");
    }

    private static Map<String, Object> snapshot(Opportunity o, PipelineStage stage) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", o.getName());
        values.put("accountId", o.getAccountId().toString());
        values.put("stage", stage.getName());
        if (o.getContactId() != null) values.put("contactId", o.getContactId().toString());
        if (o.getAmount() != null) {
            values.put("amount", o.getAmount().toPlainString());
            values.put("currency", o.getCurrency());
        }
        if (o.getExpectedCloseOn() != null) values.put("expectedCloseOn", o.getExpectedCloseOn().toString());
        if (o.getOwnerId() != null) values.put("ownerId", o.getOwnerId().toString());
        return values;
    }

    private static OpportunityDetails withOwner(OpportunityDetails d, UUID owner) {
        return new OpportunityDetails(d.name(), d.accountId(), d.contactId(), d.amount(), d.currency(),
                d.expectedCloseOn(), owner, d.description());
    }

    private static void checkVersion(Opportunity o, Long version) {
        if (version == null) {
            throw ApiProblem.badRequestField("version", "Reload the record and try again.");
        }
        if (o.getVersion() != version) {
            throw ApiProblem.conflict(STALE);
        }
    }

    private static PartyRef party(Map<UUID, PartyBrief> parties, UUID id) {
        PartyBrief p = id == null ? null : parties.get(id);
        return p == null ? null : new PartyRef(p.id(), p.name());
    }

    private static MemberRef member(Map<UUID, Members.Member> people, UUID id) {
        Members.Member m = id == null ? null : people.get(id);
        return m == null ? null : new MemberRef(m.id(), m.name());
    }
}
