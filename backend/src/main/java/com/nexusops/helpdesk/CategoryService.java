package com.nexusops.helpdesk;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.collaboration.MemberRef;
import com.nexusops.helpdesk.domain.TicketCategory;
import com.nexusops.helpdesk.domain.TicketCategoryRepository;
import com.nexusops.identity.Members;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.web.ApiProblem;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ticket categories (D5): unique names ignoring case, an optional default assignee for routing, archive/restore. */
@Service
public class CategoryService {

    static final String NOT_FOUND = "Record not found.";
    static final String STALE = "This record was changed by someone else. Reload and try again.";
    static final String ARCHIVED = "This record is archived.";
    static final String UNKNOWN = "Choose a category in this workspace.";
    static final String NAME_TAKEN = "Another category already uses this name.";
    static final String NOT_A_MEMBER = "Choose an active team member.";
    private static final String LOCK = "helpdesk-categories";
    private static final List<String> DEFAULTS = List.of("General", "Billing", "Product issue", "Delivery");

    private final TicketCategoryRepository categories;
    private final Members members;
    private final TenantLocks locks;
    private final AuditService audit;

    CategoryService(TicketCategoryRepository categories, Members members, TenantLocks locks, AuditService audit) {
        this.categories = categories;
        this.members = members;
        this.locks = locks;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<CategoryView> list(boolean archived) {
        TenantContext.requireTenantId();
        List<TicketCategory> found = archived ? categories.findByArchivedAtIsNotNullOrderByPositionAscNameAsc()
                : categories.findByArchivedAtIsNullOrderByPositionAscNameAsc();
        return views(found);
    }

    @Transactional
    public CategoryView create(CategoryCommand command) {
        TenantContext.requireTenantId();
        String name = name(command.name());
        String description = Text.optional(command.description(), 500, "description");
        UUID assignee = assignee(command.defaultAssigneeId());
        locks.lock(LOCK);
        if (categories.existsByNameKey(key(name))) {
            throw ApiProblem.conflictField("name", NAME_TAKEN);
        }
        TicketCategory category = new TicketCategory(Ids.newId(), name, key(name), description, assignee,
                (int) categories.count());
        categories.saveAndFlush(category);
        audit.record(AuditEntry.of("TicketCategoryCreated", "TicketCategory", category.getId())
                .withAfter(snapshot(category)));
        return views(List.of(category)).getFirst();
    }

    @Transactional
    public CategoryView update(UUID id, CategoryCommand command, Long version) {
        TicketCategory category = find(id);
        checkVersion(category, version);
        String name = name(command.name());
        String description = Text.optional(command.description(), 500, "description");
        UUID assignee = assignee(command.defaultAssigneeId());
        requireNotArchived(category);
        locks.lock(LOCK);
        if (categories.existsByNameKeyAndIdNot(key(name), id)) {
            throw ApiProblem.conflictField("name", NAME_TAKEN);
        }
        Map<String, Object> before = snapshot(category);
        category.apply(name, key(name), description, assignee);
        categories.flush();
        audit.record(AuditEntry.of("TicketCategoryUpdated", "TicketCategory", id).withBefore(before)
                .withAfter(snapshot(category)));
        return views(List.of(category)).getFirst();
    }

    @Transactional
    public CategoryView archive(UUID id) {
        TicketCategory category = find(id);
        if (!category.isArchived()) {
            category.archive(Instant.now());
            categories.flush();
            audit.record(AuditEntry.of("TicketCategoryArchived", "TicketCategory", id).withBefore(snapshot(category)));
        }
        return views(List.of(category)).getFirst();
    }

    @Transactional
    public CategoryView restore(UUID id) {
        TicketCategory category = find(id);
        if (category.isArchived()) {
            category.restore();
            categories.flush();
            audit.record(AuditEntry.of("TicketCategoryRestored", "TicketCategory", id).withAfter(snapshot(category)));
        }
        return views(List.of(category)).getFirst();
    }

    /** New workspaces (HelpDeskSetup): the default categories, once. */
    @Transactional
    public void seedDefaults() {
        TenantContext.requireTenantId();
        locks.lock(LOCK);
        if (categories.count() > 0) {
            return;
        }
        for (int i = 0; i < DEFAULTS.size(); i++) {
            String name = DEFAULTS.get(i);
            categories.save(new TicketCategory(Ids.newId(), name, key(name), null, null, i));
        }
        categories.flush();
    }

    /** A category referenced from a request body: unknown or other-tenant → 400 on {@code field}. */
    TicketCategory resolve(UUID id, String field) {
        if (id == null) {
            throw ApiProblem.badRequestField(field, UNKNOWN);
        }
        return categories.findById(id).orElseThrow(() -> ApiProblem.badRequestField(field, UNKNOWN));
    }

    static void requireNotArchived(TicketCategory category) {
        if (category.isArchived()) {
            throw ApiProblem.conflict(ARCHIVED);
        }
    }

    Map<UUID, TicketCategory> byIds(Collection<UUID> ids) {
        return categories.findAllById(ids).stream()
                .collect(Collectors.toMap(TicketCategory::getId, Function.identity()));
    }

    static CategoryRef ref(TicketCategory category) {
        return category == null ? null : new CategoryRef(category.getId(), category.getName());
    }

    private UUID assignee(UUID id) {
        if (id == null) {
            return null;
        }
        return members.findActive(id).map(Members.Member::id)
                .orElseThrow(() -> ApiProblem.badRequestField("defaultAssigneeId", NOT_A_MEMBER));
    }

    private List<CategoryView> views(List<TicketCategory> list) {
        Map<UUID, Members.Member> people = members.findAll(list.stream().map(TicketCategory::getDefaultAssigneeId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return list.stream().map(c -> {
            Members.Member m = c.getDefaultAssigneeId() == null ? null : people.get(c.getDefaultAssigneeId());
            return new CategoryView(c.getId(), c.getName(), c.getDescription(),
                    m == null ? null : new MemberRef(m.id(), m.name()), c.getPosition(), c.getArchivedAt(),
                    c.getVersion());
        }).toList();
    }

    private TicketCategory find(UUID id) {
        TenantContext.requireTenantId();
        return categories.findById(id).orElseThrow(() -> ApiProblem.notFound(NOT_FOUND));
    }

    private static void checkVersion(TicketCategory category, Long version) {
        if (version == null) {
            throw ApiProblem.badRequestField("version", "Reload the record and try again.");
        }
        if (category.getVersion() != version) {
            throw ApiProblem.conflict(STALE);
        }
    }

    private static String name(String raw) {
        return Text.required(raw, 80, "name").replaceAll("\\s+", " ");
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> snapshot(TicketCategory c) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", c.getName());
        values.put("description", c.getDescription());
        values.put("defaultAssigneeId", c.getDefaultAssigneeId() == null ? null : c.getDefaultAssigneeId().toString());
        return values;
    }
}
