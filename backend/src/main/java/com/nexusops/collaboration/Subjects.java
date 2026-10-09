package com.nexusops.collaboration;

import com.nexusops.shared.security.CurrentAuthorities;
import com.nexusops.shared.web.ApiProblem;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Resolves and authorizes subjects: unknown type 400 → no read permission 403 → not found 404 → archived 409. */
@Service
public class Subjects {

    static final String FORBIDDEN = "You do not have permission to perform this action.";

    private final Map<String, SubjectResolver> resolvers;

    Subjects(List<SubjectResolver> resolvers) {
        this.resolvers = resolvers.stream().collect(Collectors.toMap(SubjectResolver::type, Function.identity()));
    }

    /** Whether a subject type exists (a resolver is registered for it). */
    public boolean isKnownType(String type) {
        return type != null && resolvers.containsKey(type);
    }

    public boolean canRead(String type) {
        SubjectResolver resolver = type == null ? null : resolvers.get(type);
        return resolver != null && CurrentAuthorities.has(resolver.readPermission());
    }

    public SubjectRef requireReadable(String type, UUID id) {
        SubjectResolver resolver = type == null ? null : resolvers.get(type);
        if (resolver == null) {
            throw ApiProblem.badRequestField("subjectType", "Unknown record type.");
        }
        if (!CurrentAuthorities.has(resolver.readPermission())) {
            throw ApiProblem.forbidden(FORBIDDEN);
        }
        if (id == null) {
            throw ApiProblem.badRequestField("subjectId", "Choose a record.");
        }
        return resolver.find(id).orElseThrow(() -> ApiProblem.notFound("Record not found."));
    }

    /** Readable and not archived: archived records take no new activities, tasks or documents. */
    public SubjectRef requireWritable(String type, UUID id) {
        SubjectRef subject = requireReadable(type, id);
        if (subject.archived()) {
            throw ApiProblem.conflict("This record is archived.");
        }
        return subject;
    }

    /** Subjects of one type the caller may read; empty when they may not (labels stay hidden). */
    public Map<UUID, SubjectRef> labels(String type, Collection<UUID> ids) {
        SubjectResolver resolver = resolvers.get(type);
        if (resolver == null || ids.isEmpty() || !CurrentAuthorities.has(resolver.readPermission())) {
            return Map.of();
        }
        return resolver.findAll(ids);
    }

    /** 400 for an unknown type (list filters). */
    public void requireKnownType(String type) {
        if (type == null || !resolvers.containsKey(type)) {
            throw ApiProblem.badRequestField("subjectType", "Unknown record type.");
        }
    }
}
