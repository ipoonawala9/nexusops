package com.nexusops.collaboration;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.collaboration.domain.Task;
import com.nexusops.collaboration.domain.TaskDetails;
import com.nexusops.collaboration.domain.TaskRepository;
import com.nexusops.identity.Members;
import com.nexusops.notifications.MailRequested;
import com.nexusops.notifications.OutgoingMail;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.security.CurrentAuthorities;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.shared.web.PageResponse;
import com.nexusops.shared.web.Paging;
import com.nexusops.tenancy.TenantDirectory;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tasks. Managers (TASK_MANAGE) create and edit any task; an assignee may change the status of their own task with
 * TASK_READ alone. Assigning someone other than yourself emails them after commit.
 */
@Service
public class TaskService {

    static final String NOT_FOUND = "Record not found.";
    static final String STALE = "This record was changed by someone else. Reload and try again.";
    static final String FORBIDDEN = "You do not have permission to perform this action.";
    static final String NOT_A_MEMBER = "Choose an active member of this workspace.";

    private final TaskRepository tasks;
    private final Subjects subjects;
    private final Members members;
    private final TenantDirectory tenants;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final String appBaseUrl;

    TaskService(TaskRepository tasks, Subjects subjects, Members members, TenantDirectory tenants, AuditService audit,
            ApplicationEventPublisher events, @Value("${nexusops.app.base-url}") String appBaseUrl) {
        this.tasks = tasks;
        this.subjects = subjects;
        this.members = members;
        this.tenants = tenants;
        this.audit = audit;
        this.events = events;
        this.appBaseUrl = appBaseUrl;
    }

    @Transactional(readOnly = true)
    public TaskView get(UUID id) {
        return views(List.of(find(id))).getFirst();
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskView> list(TaskQuery query, Integer page, Integer size) {
        TenantContext.requireTenantId();
        Specification<Task> spec = (root, cq, cb) -> cb.conjunction();
        String assignee = query.assignee() == null ? "" : query.assignee().strip();
        if (assignee.equals("me")) {
            UUID me = currentUser();
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("assigneeId"), me));
        } else if (assignee.equals("unassigned")) {
            spec = spec.and((root, cq, cb) -> cb.isNull(root.get("assigneeId")));
        } else if (!assignee.isEmpty()) {
            UUID id = parseUuid(assignee, "assignee");
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("assigneeId"), id));
        }
        Set<TaskStatus> statuses = parseStatuses(query.status());
        if (!statuses.isEmpty()) {
            spec = spec.and((root, cq, cb) -> root.get("status").in(statuses));
        }
        if (query.subjectType() != null || query.subjectId() != null) {
            subjects.requireKnownType(query.subjectType());
            if (query.subjectId() == null) {
                throw ApiProblem.badRequestField("subjectId", "Choose a record.");
            }
            spec = spec.and((root, cq, cb) -> cb.and(cb.equal(root.get("subjectType"), query.subjectType()),
                    cb.equal(root.get("subjectId"), query.subjectId())));
        }
        String q = Text.optional(query.q(), 100, "q");
        if (q != null) {
            String like = Text.containsPattern(q);
            spec = spec.and((root, cq, cb) -> cb.like(cb.lower(root.get("title")), like, '\\'));
        }
        Sort sort = Sort.by(Sort.Order.asc("dueOn").nullsLast(), Sort.Order.desc("createdAt"), Sort.Order.asc("id"));
        Page<Task> result = tasks.findAll(spec, Paging.of(page, size, sort));
        List<TaskView> views = views(result.getContent());
        return new PageResponse<>(views, result.getNumber(), result.getSize(), result.getTotalElements());
    }

    @Transactional
    public TaskView create(TaskCommand command) {
        TenantContext.requireTenantId();
        TaskDetails details = validate(command, null);
        Task task = new Task(Ids.newId(), details, currentUser());
        tasks.saveAndFlush(task);
        audit.record(AuditEntry.of("TaskCreated", "Task", task.getId()).withAfter(snapshot(task)));
        notifyAssignee(task, null);
        return get(task.getId());
    }

    @Transactional
    public TaskView update(UUID id, TaskCommand command, Long version) {
        Task task = find(id);
        if (version == null) {
            throw ApiProblem.badRequestField("version", "Reload the record and try again.");
        }
        if (task.getVersion() != version) {
            throw ApiProblem.conflict(STALE);
        }
        TaskDetails details = validate(command, task);
        UUID previousAssignee = task.getAssigneeId();
        Map<String, Object> before = snapshot(task);
        task.apply(details);
        tasks.flush();
        audit.record(AuditEntry.of("TaskUpdated", "Task", id).withBefore(before).withAfter(snapshot(task)));
        notifyAssignee(task, previousAssignee);
        return get(id);
    }

    @Transactional
    public TaskView changeStatus(UUID id, TaskStatus status) {
        Task task = find(id);
        boolean manager = CurrentAuthorities.has(CollaborationPermissions.TASK_MANAGE);
        boolean assignee = currentUser().equals(task.getAssigneeId());
        if (!manager && !assignee) {
            throw ApiProblem.forbidden(FORBIDDEN);
        }
        if (status == null) {
            throw ApiProblem.badRequestField("status", "Choose a status.");
        }
        if (task.getStatus() != status) {
            TaskStatus before = task.getStatus();
            task.changeStatus(status, Instant.now());
            tasks.flush();
            audit.record(AuditEntry.of("TaskStatusChanged", "Task", id).withBefore(Map.of("status", before.name()))
                    .withAfter(Map.of("status", status.name())));
        }
        return get(id);
    }

    @Transactional(readOnly = true)
    public List<AssigneeView> assignees(String q) {
        return members.searchActive(q, 20).stream().map(m -> new AssigneeView(m.id(), m.name(), m.email())).toList();
    }

    private Task find(UUID id) {
        TenantContext.requireTenantId();
        return tasks.findById(id).orElseThrow(() -> ApiProblem.notFound(NOT_FOUND));
    }

    /** {@code current} is null on create. Unchanged assignees and subjects aren't re-checked (they may since be
     * disabled or archived, which mustn't block editing the rest of the task). */
    private TaskDetails validate(TaskCommand command, Task current) {
        String title = Text.required(command.title(), 200, "title").replaceAll("\\s+", " ");
        String description = Text.optional(command.description(), 5000, "description");
        TaskPriority priority = command.priority() == null ? TaskPriority.NORMAL : command.priority();
        UUID assigneeId = command.assigneeId();
        if (assigneeId != null && (current == null || !assigneeId.equals(current.getAssigneeId()))
                && members.findActive(assigneeId).isEmpty()) {
            throw ApiProblem.badRequestField("assigneeId", NOT_A_MEMBER);
        }
        String subjectType = command.subjectType() == null || command.subjectType().isBlank() ? null
                : command.subjectType().strip();
        UUID subjectId = command.subjectId();
        if (subjectType != null || subjectId != null) {
            boolean unchanged = current != null && Objects.equals(subjectType, current.getSubjectType())
                    && Objects.equals(subjectId, current.getSubjectId());
            if (!unchanged) {
                SubjectRef subject = subjects.requireWritable(subjectType, subjectId);
                subjectType = subject.type();
            }
        }
        return new TaskDetails(title, description, priority, command.dueOn(), assigneeId, subjectType, subjectId);
    }

    private List<TaskView> views(List<Task> page) {
        Map<UUID, Members.Member> people = members.findAll(page.stream()
                .flatMap(t -> Stream.of(t.getAssigneeId(), t.getCreatedBy())).filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        Map<String, Map<UUID, SubjectRef>> labels = new HashMap<>();
        page.stream().filter(t -> t.getSubjectType() != null)
                .collect(Collectors.groupingBy(Task::getSubjectType,
                        Collectors.mapping(Task::getSubjectId, Collectors.toSet())))
                .forEach((type, ids) -> labels.put(type, subjects.labels(type, ids)));
        return page.stream().map(t -> {
            SubjectRef subject = null;
            if (t.getSubjectType() != null) {
                subject = labels.getOrDefault(t.getSubjectType(), Map.of()).getOrDefault(t.getSubjectId(),
                        new SubjectRef(t.getSubjectType(), t.getSubjectId(), null, false));
            }
            return new TaskView(t.getId(), t.getTitle(), t.getDescription(), t.getStatus(), t.getPriority(),
                    t.getDueOn(), ref(people, t.getAssigneeId()), subject, ref(people, t.getCreatedBy()),
                    t.getCompletedAt(), t.getCreatedAt(), t.getUpdatedAt(), t.getVersion());
        }).toList();
    }

    private static MemberRef ref(Map<UUID, Members.Member> people, UUID id) {
        Members.Member member = id == null ? null : people.get(id);
        return member == null ? null : new MemberRef(member.id(), member.name());
    }

    private void notifyAssignee(Task task, UUID previousAssignee) {
        UUID assignee = task.getAssigneeId();
        if (assignee == null || assignee.equals(previousAssignee) || assignee.equals(currentUser())) {
            return;
        }
        Map<UUID, Members.Member> people = members.findAll(List.of(assignee, currentUser()));
        Members.Member to = people.get(assignee);
        Members.Member actor = people.get(currentUser());
        String workspace = tenants.current().name();
        events.publishEvent(new MailRequested(new OutgoingMail(to.email(), "You've been assigned: " + task.getTitle(), """
                Hello %s,

                %s assigned you a task in %s:

                  %s
                  Due: %s

                See your tasks: %s/app/tasks
                """.formatted(to.name(), actor == null ? "A teammate" : actor.name(), workspace, task.getTitle(),
                task.getDueOn() == null ? "no due date" : task.getDueOn().toString(), appBaseUrl))));
    }

    private static Map<String, Object> snapshot(Task task) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("title", task.getTitle());
        values.put("status", task.getStatus().name());
        values.put("priority", task.getPriority().name());
        if (task.getDueOn() != null) values.put("dueOn", task.getDueOn().toString());
        if (task.getAssigneeId() != null) values.put("assigneeId", task.getAssigneeId().toString());
        if (task.getSubjectType() != null) {
            values.put("subjectType", task.getSubjectType());
            values.put("subjectId", task.getSubjectId().toString());
        }
        return values;
    }

    private static UUID currentUser() {
        return TenantContext.userId().orElseThrow(() -> new IllegalStateException("No user bound"));
    }

    private static UUID parseUuid(String raw, String field) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException invalid) {
            throw ApiProblem.badRequestField(field, "Use me, unassigned or a member id.");
        }
    }

    private static Set<TaskStatus> parseStatuses(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        try {
            return Arrays.stream(raw.split(",")).map(s -> TaskStatus.valueOf(s.strip().toUpperCase(Locale.ROOT)))
                    .collect(Collectors.toSet());
        } catch (IllegalArgumentException invalid) {
            throw ApiProblem.badRequestField("status", "Use OPEN, IN_PROGRESS, DONE or CANCELLED.");
        }
    }
}
