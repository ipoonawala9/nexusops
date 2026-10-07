package com.nexusops.collaboration;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Implemented by each module that owns a record type (PARTY: directory, PRODUCT: catalog, …). Tenant-scoped. */
public interface SubjectResolver {

    /** Upper-case code stored in subject_type columns, e.g. "PARTY". */
    String type();

    /** Permission needed to see this type's records and anything attached to them. */
    String readPermission();

    Optional<SubjectRef> find(UUID id);

    Map<UUID, SubjectRef> findAll(Collection<UUID> ids);

    /**
     * Up to {@code limit} records matching {@code pattern}, a lower-case LIKE pattern from Text.containsPattern
     * (escape character '\'). Types that aren't searchable keep the default.
     */
    default List<SearchHit> search(String pattern, int limit) {
        return List.of();
    }
}
