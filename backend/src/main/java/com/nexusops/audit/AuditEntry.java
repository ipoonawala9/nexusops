package com.nexusops.audit;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One auditable action. Actor and tenant come from TenantContext unless the entry names its actor
 * explicitly ({@link #asPlatformActor}); request metadata from the current HTTP request, unless
 * {@link #withoutClientDetails} drops the client's IP and user agent. Keys that look secret are dropped from
 * before/after/metadata.
 */
public record AuditEntry(
        String action,
        String entityType,
        String entityId,
        Map<String, Object> before,
        Map<String, Object> after,
        Map<String, Object> metadata,
        ActorType actorType,
        UUID actorId,
        boolean clientDetailsSuppressed) {

    public static AuditEntry of(String action, String entityType, Object entityId) {
        return new AuditEntry(action, entityType, entityId == null ? null : entityId.toString(), null, null, null, null,
                null, false);
    }

    public AuditEntry withBefore(Map<String, Object> value) {
        return new AuditEntry(action, entityType, entityId, value, after, metadata, actorType, actorId,
                clientDetailsSuppressed);
    }

    public AuditEntry withAfter(Map<String, Object> value) {
        return new AuditEntry(action, entityType, entityId, before, value, metadata, actorType, actorId,
                clientDetailsSuppressed);
    }

    public AuditEntry withMetadata(Map<String, Object> value) {
        return new AuditEntry(action, entityType, entityId, before, after, value, actorType, actorId,
                clientDetailsSuppressed);
    }

    public AuditEntry asActor(ActorType value) {
        return new AuditEntry(action, entityType, entityId, before, after, metadata, value, actorId,
                clientDetailsSuppressed);
    }

    /** A platform operator acted: recorded as actor_type PLATFORM with this id, whatever TenantContext holds. */
    public AuditEntry asPlatformActor(UUID platformUserId) {
        Objects.requireNonNull(platformUserId, "platformUserId");
        return new AuditEntry(action, entityType, entityId, before, after, metadata, ActorType.PLATFORM, platformUserId,
                clientDetailsSuppressed);
    }

    /**
     * Stores the row without the requester's IP address and user agent (request and correlation ids stay). Used where
     * the row is visible to people who must not see the requester's network details, e.g. a platform operator's
     * action in a workspace's own log (ADR-0007).
     */
    public AuditEntry withoutClientDetails() {
        return new AuditEntry(action, entityType, entityId, before, after, metadata, actorType, actorId, true);
    }
}
