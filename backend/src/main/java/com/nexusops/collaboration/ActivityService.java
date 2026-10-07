package com.nexusops.collaboration;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.collaboration.domain.Activity;
import com.nexusops.collaboration.domain.ActivityRepository;
import com.nexusops.identity.Members;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.shared.web.PageResponse;
import com.nexusops.shared.web.Paging;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The activity timeline of a subject. Reading needs the subject's read permission; logging also needs ACTIVITY_CREATE. */
@Service
public class ActivityService {

    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final int MAX_RELATED = 200;

    private final ActivityRepository activities;
    private final Subjects subjects;
    private final Members members;
    private final AuditService audit;
    private final List<SubjectRelations> relations;

    ActivityService(ActivityRepository activities, Subjects subjects, Members members, AuditService audit,
            List<SubjectRelations> relations) {
        this.activities = activities;
        this.subjects = subjects;
        this.members = members;
        this.audit = audit;
        this.relations = relations;
    }

    @Transactional(readOnly = true)
    public PageResponse<ActivityView> list(String subjectType, UUID subjectId, boolean includeRelated, Integer page,
            Integer size) {
        SubjectRef subject = subjects.requireReadable(subjectType, subjectId);
        Map<String, Set<UUID>> keys = new LinkedHashMap<>();
        keys.computeIfAbsent(subject.type(), k -> new LinkedHashSet<>()).add(subject.id());
        if (includeRelated) {
            relations.stream().flatMap(r -> r.related(subject.type(), subject.id()).stream())
                    .filter(k -> subjects.canRead(k.type())).limit(MAX_RELATED)
                    .forEach(k -> keys.computeIfAbsent(k.type(), t -> new LinkedHashSet<>()).add(k.id()));
        }
        Specification<Activity> spec = (root, cq, cb) -> cb.or(keys.entrySet().stream()
                .map(e -> cb.and(cb.equal(root.get("subjectType"), e.getKey()), root.get("subjectId").in(e.getValue())))
                .toArray(jakarta.persistence.criteria.Predicate[]::new));
        Page<Activity> result = activities.findAll(spec,
                Paging.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt", "id")));
        Map<UUID, Members.Member> authors = members.findAll(
                result.getContent().stream().map(Activity::getAuthorId).filter(Objects::nonNull).toList());
        Map<String, Map<UUID, SubjectRef>> labels = new HashMap<>();
        result.getContent().stream().collect(Collectors.groupingBy(Activity::getSubjectType,
                        Collectors.mapping(Activity::getSubjectId, Collectors.toSet())))
                .forEach((type, ids) -> labels.put(type, subjects.labels(type, ids)));
        return PageResponse.from(result, a -> view(a, authors, labels.getOrDefault(a.getSubjectType(), Map.of())
                .getOrDefault(a.getSubjectId(), new SubjectRef(a.getSubjectType(), a.getSubjectId(), null, false))));
    }

    @Transactional
    public ActivityView log(ActivityCommand command) {
        SubjectRef subject = subjects.requireWritable(command.subjectType(), command.subjectId());
        if (command.type() == null) {
            throw ApiProblem.badRequestField("type", "Choose a type.");
        }
        String summary = Text.required(command.summary(), 200, "summary");
        String body = Text.optional(command.body(), 10_000, "body");
        Instant now = Instant.now();
        Instant occurredAt = command.occurredAt() == null ? now : command.occurredAt();
        if (occurredAt.isAfter(now.plus(CLOCK_SKEW))) {
            throw ApiProblem.badRequestField("occurredAt", "An activity can't be in the future.");
        }
        UUID author = TenantContext.userId().orElseThrow(() -> new IllegalStateException("No user bound"));
        Activity activity = new Activity(Ids.newId(), subject.type(), subject.id(), command.type(), summary, body,
                occurredAt, author);
        activities.saveAndFlush(activity);
        audit.record(AuditEntry.of("ActivityLogged", "Activity", activity.getId()).withMetadata(Map.of(
                "subjectType", subject.type(), "subjectId", subject.id().toString(), "type", command.type().name())));
        return view(activity, members.findAll(List.of(author)), subject);
    }

    private static ActivityView view(Activity a, Map<UUID, Members.Member> authors, SubjectRef subject) {
        Members.Member author = a.getAuthorId() == null ? null : authors.get(a.getAuthorId());
        return new ActivityView(a.getId(), a.getSubjectType(), a.getSubjectId(), a.getType(), a.getSummary(),
                a.getBody(), a.getOccurredAt(), author == null ? null : new MemberRef(author.id(), author.name()),
                a.getCreatedAt(), subject);
    }
}
