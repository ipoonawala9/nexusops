package com.nexusops.helpdesk;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.catalog.CatalogPermissions;
import com.nexusops.catalog.ProductBrief;
import com.nexusops.catalog.ProductService;
import com.nexusops.collaboration.MemberRef;
import com.nexusops.collaboration.SubjectRef;
import com.nexusops.collaboration.Subjects;
import com.nexusops.directory.DirectoryPermissions;
import com.nexusops.directory.PartyBrief;
import com.nexusops.directory.PartyRef;
import com.nexusops.directory.PartyService;
import com.nexusops.helpdesk.domain.Ticket;
import com.nexusops.helpdesk.domain.TicketCategory;
import com.nexusops.helpdesk.domain.TicketRepository;
import com.nexusops.identity.Members;
import com.nexusops.notifications.MailRequested;
import com.nexusops.notifications.OutgoingMail;
import com.nexusops.shared.Ids;
import com.nexusops.shared.NumberSequences;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.security.CurrentAuthorities;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.shared.web.PageResponse;
import com.nexusops.shared.web.Paging;
import com.nexusops.tenancy.TenantDirectory;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tickets (D3–D8): context references, routing by category, assignment, the workflow and its SLA clock. */
@Service
public class TicketService {

    static final String NOT_FOUND = "Record not found.";
    static final String STALE = "This record was changed by someone else. Reload and try again.";
    static final String FORBIDDEN = "You do not have permission to perform this action.";
    static final String ARCHIVED = "This record is archived.";
    static final String CLOSED = "A closed ticket can't be changed.";
    static final String NOTE_NEEDED = "Add a resolution note.";
    static final String UNKNOWN_PARTY = "Choose a person or organization in this workspace.";
    static final String NOT_A_MEMBER = "Choose an active team member.";
    static final String NOT_ALLOWED = "This status change isn't allowed.";
    static final String SEQUENCE = "TICKET";

    private final TicketRepository tickets;
    private final NumberSequences numbers;
    private final CategoryService categories;
    private final SlaPolicyService policies;
    private final HelpDeskQueries queries;
    private final HelpDeskSearch search;
    private final ArticleService articles;
    private final PartyService parties;
    private final ProductService products;
    private final Subjects subjects;
    private final Members members;
    private final TenantDirectory tenants;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final String appBaseUrl;

    TicketService(TicketRepository tickets, NumberSequences numbers, CategoryService categories,
            SlaPolicyService policies, HelpDeskQueries queries, HelpDeskSearch search, ArticleService articles,
            PartyService parties, ProductService products,
            Subjects subjects, Members members, TenantDirectory tenants, AuditService audit,
            ApplicationEventPublisher events, @Value("${nexusops.app.base-url}") String appBaseUrl) {
        this.tickets = tickets;
        this.numbers = numbers;
        this.categories = categories;
        this.policies = policies;
        this.queries = queries;
        this.search = search;
        this.articles = articles;
        this.parties = parties;
        this.products = products;
        this.subjects = subjects;
        this.members = members;
        this.tenants = tenants;
        this.audit = audit;
        this.events = events;
        this.appBaseUrl = appBaseUrl;
    }

    /** Validated input: every reference resolved. */
    private record Draft(String subject, String description, UUID requesterId, UUID productId, String linkedType,
            UUID linkedId, UUID categoryId, Priority priority, Channel channel, UUID assignee) {}

    @Transactional(readOnly = true)
    public TicketView get(UUID id) {
        return view(find(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<TicketSummary> list(TicketQuery query, Integer page, Integer size) {
        TenantContext.requireTenantId();
        Pageable paging = Paging.of(page, size);
        HelpDeskQueries.Page found = queries.ticketPage(query, TenantContext.userId().orElse(null),
                paging.getPageSize(), paging.getOffset());
        return new PageResponse<>(ticketsInOrder(found.ids()), paging.getPageNumber(), paging.getPageSize(),
                found.total());
    }

    /** What sits next to a ticket for the agent: the requester's history, likely duplicates, helpful articles (D12). */
    @Transactional(readOnly = true)
    public TicketContext context(UUID id) {
        Ticket ticket = find(id);
        List<TicketSummary> previous = ticketsInOrder(search.previousTickets(ticket.getRequesterId(), id, 10));
        List<TicketSummary> duplicates = ticketsInOrder(search.duplicateCandidates(ticket, 5));
        List<ArticleSummary> suggested = CurrentAuthorities.has(HelpDeskPermissions.ARTICLE_READ)
                ? articles.summaries(search.suggestedArticles(ticket.getSubject() + " " + ticket.getDescription(), 5))
                : List.of();
        return new TicketContext(previous, duplicates, suggested);
    }

    private List<TicketSummary> ticketsInOrder(List<UUID> ids) {
        Map<UUID, Ticket> byId = tickets.findAllById(ids).stream()
                .collect(Collectors.toMap(Ticket::getId, Function.identity()));
        return summaries(ids.stream().map(byId::get).filter(Objects::nonNull).toList());
    }

    @Transactional
    public TicketView create(TicketCommand command) {
        TenantContext.requireTenantId();
        Draft draft = validate(command, null);
        UUID assignee = draft.assignee();
        Instant now = Instant.now();
        SlaTargets targets = policies.targets(draft.priority());
        Ticket ticket = new Ticket(Ids.newId(), numbers.next(SEQUENCE, "T-"), draft.priority(), assignee,
                TenantContext.userId().orElse(null), now, SlaClock.firstResponseDue(now, targets),
                SlaClock.resolutionDue(now, 0, targets));
        apply(ticket, draft);
        tickets.saveAndFlush(ticket);
        audit.record(AuditEntry.of("TicketCreated", "Ticket", ticket.getId()).withAfter(snapshot(ticket)));
        notifyAssignee(ticket, null);
        return view(ticket);
    }

    @Transactional
    public TicketView update(UUID id, TicketCommand command, Long version) {
        Ticket ticket = find(id);
        checkVersion(ticket, version);
        requireNotClosed(ticket);
        Draft draft = validate(command, ticket);
        Map<String, Object> before = snapshot(ticket);
        apply(ticket, draft);
        if (draft.priority() != ticket.getPriority()) {
            SlaTargets targets = policies.targets(draft.priority());
            ticket.reprioritize(draft.priority(), SlaClock.firstResponseDue(ticket.getCreatedAt(), targets),
                    SlaClock.resolutionDue(ticket.getResolutionClockStartedAt(), ticket.getPausedSeconds(), targets));
        }
        tickets.flush();
        audit.record(AuditEntry.of("TicketUpdated", "Ticket", id).withBefore(before).withAfter(snapshot(ticket)));
        return view(ticket);
    }

    @Transactional
    public TicketView assign(UUID id, UUID assigneeId, Long version) {
        Ticket ticket = find(id);
        if (version == null) {
            throw ApiProblem.badRequestField("version", "Reload the record and try again.");
        }
        UUID assignee = assigneeId == null ? null : activeMember(assigneeId, "assigneeId");
        checkVersion(ticket, version);
        requireNotClosed(ticket);
        UUID previous = ticket.getAssigneeId();
        ticket.assign(assignee, Instant.now());
        tickets.flush();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("assigneeId", assignee == null ? null : assignee.toString());
        after.put("status", ticket.getStatus().name());
        audit.record(AuditEntry.of("TicketAssigned", "Ticket", id)
                .withBefore(Collections.singletonMap("assigneeId", previous == null ? null : previous.toString()))
                .withAfter(after));
        notifyAssignee(ticket, previous);
        return view(ticket);
    }

    @Transactional
    public TicketView changeStatus(UUID id, TicketStatus target, String rawNote, Long version) {
        if (target == null) {
            throw ApiProblem.badRequestField("status", "Choose a status.");
        }
        String note = Text.optional(rawNote, 2000, "note");
        if (target == TicketStatus.RESOLVED && note == null) {
            throw ApiProblem.badRequestField("note", NOTE_NEEDED);
        }
        Ticket ticket = find(id);
        checkVersion(ticket, version);
        requireNotClosed(ticket);
        TicketStatus from = ticket.getStatus();
        if (!from.canMoveTo(target)) {
            throw ApiProblem.conflict(NOT_ALLOWED);
        }
        moveTo(ticket, target, note, Instant.now());
        tickets.flush();
        audit.record(AuditEntry.of("TicketStatusChanged", "Ticket", id)
                .withBefore(Map.of("status", from.name())).withAfter(Map.of("status", target.name())));
        return view(ticket);
    }

    /** Active members for the assignee picker. */
    @Transactional(readOnly = true)
    public List<AgentView> agents(String q) {
        return members.searchActive(q, 20).stream().map(m -> new AgentView(m.id(), m.name(), m.email())).toList();
    }

    /** The workflow's side effects on the SLA clock (D6, D8). Validation is the caller's. */
    void moveTo(Ticket ticket, TicketStatus target, String note, Instant now) {
        SlaTargets targets = policies.targets(ticket.getPriority());
        if (ticket.getStatus() == TicketStatus.PENDING) {
            long paused = SlaClock.pausedSecondsAfterResume(ticket.getPausedSeconds(), ticket.getPausedAt(), now);
            ticket.resume(paused, SlaClock.resolutionDue(ticket.getResolutionClockStartedAt(), paused, targets), now);
        }
        switch (target) {
            case PENDING -> ticket.pause(now);
            case RESOLVED -> ticket.resolve(note, now);
            case CLOSED -> ticket.close(now);
            case OPEN -> {
                if (ticket.getStatus() == TicketStatus.RESOLVED) {
                    ticket.reopen(SlaClock.resolutionDue(now, 0, targets), now);
                } else {
                    ticket.open(now);
                }
            }
            case NEW -> throw new IllegalStateException("No ticket moves back to NEW");
        }
    }

    Ticket find(UUID id) {
        TenantContext.requireTenantId();
        return tickets.findById(id).orElseThrow(() -> ApiProblem.notFound(NOT_FOUND));
    }

    static void checkVersion(Ticket ticket, Long version) {
        if (version == null) {
            throw ApiProblem.badRequestField("version", "Reload the record and try again.");
        }
        if (ticket.getVersion() != version) {
            throw ApiProblem.conflict(STALE);
        }
    }

    static void requireNotClosed(Ticket ticket) {
        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw ApiProblem.conflict(CLOSED);
        }
    }

    /**
     * Body checks, then references that need no permission (category, linked type), then those that may answer 403
     * (product, linked record, requester — last), then archived checks for new or changed references (R3/R4 order).
     */
    private Draft validate(TicketCommand c, Ticket current) {
        String subject = Text.required(c.subject(), 200, "subject").replaceAll("\\s+", " ");
        String description = Text.required(c.description(), 10_000, "description");
        Priority priority = c.priority() == null ? Priority.NORMAL : c.priority();
        Channel channel = c.channel() == null ? Channel.PHONE : c.channel();
        if (c.requesterId() == null) {
            throw ApiProblem.badRequestField("requesterId", UNKNOWN_PARTY);
        }
        if ((c.linkedType() == null) != (c.linkedId() == null)) {
            throw ApiProblem.badRequestField(c.linkedType() == null ? "linkedType" : "linkedId", "Choose a record.");
        }
        if (c.linkedType() != null && (!subjects.isKnownType(c.linkedType()) || "TICKET".equals(c.linkedType()))) {
            throw ApiProblem.badRequestField("linkedType", "Unknown record type.");
        }
        TicketCategory category = c.categoryId() == null ? null : categories.resolve(c.categoryId(), "categoryId");
        UUID explicitAssignee = c.assigneeId() != null && current == null ? activeMember(c.assigneeId(), "assigneeId") : null;
        ProductBrief product = null;
        boolean productChanged = current == null || !Objects.equals(c.productId(), current.getProductId());
        if (c.productId() != null && productChanged) {
            if (!CurrentAuthorities.has(CatalogPermissions.PRODUCT_READ)) {
                throw ApiProblem.forbidden(FORBIDDEN);
            }
            product = products.briefs(List.of(c.productId())).get(c.productId());
            if (product == null) {
                throw ApiProblem.badRequestField("productId", "Choose a product in this workspace.");
            }
        }
        boolean linkedChanged = current == null || !Objects.equals(c.linkedType(), current.getLinkedType())
                || !Objects.equals(c.linkedId(), current.getLinkedId());
        if (c.linkedType() != null && linkedChanged) {
            if (!subjects.canRead(c.linkedType())) {
                throw ApiProblem.forbidden(FORBIDDEN);
            }
            SubjectRef linked = subjects.labels(c.linkedType(), List.of(c.linkedId())).get(c.linkedId());
            if (linked == null) {
                throw ApiProblem.badRequestField("linkedId", "Choose a record in this workspace.");
            }
        }
        boolean requesterChanged = current == null || !c.requesterId().equals(current.getRequesterId());
        PartyBrief requester = null;
        if (requesterChanged) {
            if (!CurrentAuthorities.has(DirectoryPermissions.PARTY_READ)) {
                throw ApiProblem.forbidden(FORBIDDEN);
            }
            requester = parties.briefs(List.of(c.requesterId())).get(c.requesterId());
            if (requester == null) {
                throw ApiProblem.badRequestField("requesterId", UNKNOWN_PARTY);
            }
        }
        if (category != null && (current == null || !category.getId().equals(current.getCategoryId()))) {
            CategoryService.requireNotArchived(category);
        }
        if (product != null && product.archived()) {
            throw ApiProblem.conflict(ARCHIVED);
        }
        if (requester != null && requester.archived()) {
            throw ApiProblem.conflict(ARCHIVED);
        }
        return new Draft(subject, description, c.requesterId(), c.productId(), c.linkedType(), c.linkedId(),
                c.categoryId(), priority, channel,
                explicitAssignee != null ? explicitAssignee : routedAssignee(category, current));
    }

    private void apply(Ticket ticket, Draft d) {
        ticket.applyDetails(d.subject(), d.description(), d.requesterId(), d.productId(), d.linkedType(), d.linkedId(),
                d.categoryId(), d.channel());
    }

    /** The category's default assignee for a new ticket, unless that member has been deactivated since. */
    private UUID routedAssignee(TicketCategory category, Ticket current) {
        if (current != null || category == null || category.getDefaultAssigneeId() == null) {
            return null;
        }
        return members.findActive(category.getDefaultAssigneeId()).map(Members.Member::id).orElse(null);
    }

    private UUID activeMember(UUID id, String field) {
        return members.findActive(id).map(Members.Member::id)
                .orElseThrow(() -> ApiProblem.badRequestField(field, NOT_A_MEMBER));
    }

    /** Emails a new assignee (not the person who assigned themselves), after commit via MailRequested. */
    private void notifyAssignee(Ticket ticket, UUID previous) {
        UUID assignee = ticket.getAssigneeId();
        UUID actor = TenantContext.userId().orElse(null);
        if (assignee == null || assignee.equals(previous) || assignee.equals(actor)) {
            return;
        }
        Map<UUID, Members.Member> people = members.findAll(actor == null ? List.of(assignee) : List.of(assignee, actor));
        Members.Member to = people.get(assignee);
        if (to == null) {
            return;
        }
        Members.Member by = actor == null ? null : people.get(actor);
        events.publishEvent(new MailRequested(new OutgoingMail(to.email(),
                "You've been assigned " + ticket.getNumber() + ": " + ticket.getSubject(), """
                Hello %s,

                %s assigned you a ticket in %s:

                  %s · %s
                  Priority: %s
                  First response due: %s

                Open it: %s/app/helpdesk/tickets/%s
                """.formatted(to.name(), by == null ? "A teammate" : by.name(), tenants.current().name(),
                ticket.getNumber(), ticket.getSubject(), ticket.getPriority().name(),
                ticket.getFirstResponseDueAt(), appBaseUrl, ticket.getId()))));
    }

    SlaView sla(Ticket t, Instant now) {
        // The target lengths come from the ticket's own stored spans, as in HelpDeskQueries.AT_RISK, so a later edit
        // of the policy doesn't change how existing tickets are classified.
        boolean paused = t.getStatus() == TicketStatus.PENDING;
        int firstResponseMinutes = (int) Duration.between(t.getCreatedAt(), t.getFirstResponseDueAt()).toMinutes();
        int resolutionMinutes = (int) ((Duration.between(t.getResolutionClockStartedAt(), t.getResolutionDueAt())
                .toSeconds() - t.getPausedSeconds()) / 60);
        return new SlaView(t.getFirstResponseDueAt(), t.getFirstRespondedAt(),
                SlaClock.state(t.getFirstResponseDueAt(), t.getFirstRespondedAt(), now, firstResponseMinutes, null),
                t.getResolutionDueAt(), t.getResolvedAt(),
                SlaClock.state(t.getResolutionDueAt(), t.getResolvedAt(), now, resolutionMinutes,
                        paused ? t.getPausedAt() : null),
                t.getPausedAt());
    }

    TicketView view(Ticket t) {
        Instant now = Instant.now();
        PartyRef requester = requesters(List.of(t.getRequesterId())).get(t.getRequesterId());
        TicketProductRef product = null;
        if (t.getProductId() != null) {
            ProductBrief p = products.briefs(List.of(t.getProductId())).get(t.getProductId());
            product = p == null ? null : new TicketProductRef(p.id(), p.sku(), p.name());
        }
        LinkedRecord linked = null;
        if (t.getLinkedType() != null) {
            SubjectRef ref = subjects.labels(t.getLinkedType(), List.of(t.getLinkedId())).get(t.getLinkedId());
            linked = new LinkedRecord(t.getLinkedType(), t.getLinkedId(), ref == null ? null : ref.label());
        }
        TicketCategory category = t.getCategoryId() == null ? null
                : categories.byIds(List.of(t.getCategoryId())).get(t.getCategoryId());
        Map<UUID, MemberRef> people = memberRefs(new HashSet<>(Arrays.asList(t.getAssigneeId(), t.getCreatedBy())));
        return new TicketView(t.getId(), t.getNumber(), t.getSubject(), t.getDescription(), requester, product, linked,
                CategoryService.ref(category), t.getPriority(), t.getChannel(), people.get(t.getAssigneeId()),
                t.getStatus(), sla(t, now), t.getResolutionNote(), t.getReopenCount(), t.getClosedAt(),
                people.get(t.getCreatedBy()), t.getCreatedAt(), t.getUpdatedAt(), t.getVersion());
    }

    List<TicketSummary> summaries(List<Ticket> list) {
        Instant now = Instant.now();
        Map<UUID, PartyRef> requesters = requesters(list.stream().map(Ticket::getRequesterId).collect(Collectors.toSet()));
        Map<UUID, TicketCategory> cats = categories.byIds(list.stream().map(Ticket::getCategoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<UUID, MemberRef> people = memberRefs(list.stream().map(Ticket::getAssigneeId).collect(Collectors.toSet()));
        return list.stream().map(t -> new TicketSummary(t.getId(), t.getNumber(), t.getSubject(),
                requesters.get(t.getRequesterId()), CategoryService.ref(cats.get(t.getCategoryId())), t.getPriority(),
                t.getStatus(), people.get(t.getAssigneeId()), sla(t, now), t.getCreatedAt(), t.getUpdatedAt())).toList();
    }

    /** Names of requesters (shown to anyone who may read the ticket, like an opportunity's account). */
    Map<UUID, PartyRef> requesters(Collection<UUID> ids) {
        return parties.briefs(ids).values().stream()
                .collect(Collectors.toMap(PartyBrief::id, p -> new PartyRef(p.id(), p.name())));
    }

    private Map<UUID, MemberRef> memberRefs(Set<UUID> ids) {
        Set<UUID> present = new HashSet<>(ids);
        present.remove(null);
        return members.findAll(present).values().stream()
                .collect(Collectors.toMap(Members.Member::id, m -> new MemberRef(m.id(), m.name())));
    }

    private static Map<String, Object> snapshot(Ticket t) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("number", t.getNumber());
        values.put("subject", t.getSubject());
        values.put("requesterId", t.getRequesterId().toString());
        values.put("productId", t.getProductId() == null ? null : t.getProductId().toString());
        values.put("linkedType", t.getLinkedType());
        values.put("linkedId", t.getLinkedId() == null ? null : t.getLinkedId().toString());
        values.put("categoryId", t.getCategoryId() == null ? null : t.getCategoryId().toString());
        values.put("priority", t.getPriority().name());
        values.put("channel", t.getChannel().name());
        values.put("status", t.getStatus().name());
        return values;
    }
}
