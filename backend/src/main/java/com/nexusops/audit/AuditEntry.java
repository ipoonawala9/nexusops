package com.nexusops.audit;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One auditable action. Actor and tenant come from TenantContext unless the entry names its actor
 * explicitly ({@link #asPlatformActor}); request metadata from the current HTTP request. Keys that
 * look secret are dropped from before/after/metadata.
 */
public record AuditEntry(
        String action,
        String entityType,
        String entityId,
        Map<String, Object> before,
        Map<String, Object> after,
        Map<String, Object> metadata,
        ActorType actorType,
        UUID actorId) {

    public static AuditEntry of(String action, String entityType, Object entityId) {
        return new AuditEntry(action, entityType, entityId == null ? null : entityId.toString(), null, null, null, null,
                null);
    }

    public AuditEntry withBefore(Map<String, Object> value) {
        return new AuditEntry(action, entityType, entityId, value, after, metadata, actorType, actorId);
    }

    public AuditEntry withAfter(Map<String, Object> value) {
        return new AuditEntry(action, entityType, entityId, before, value, metadata, actorType, actorId);
    }

    public AuditEntry withMetadata(Map<String, Object> value) {
        return new AuditEntry(action, entityType, entityId, before, after, value, actorType, actorId);
    }

    public AuditEntry asActor(ActorType value) {
        return new AuditEntry(action, entityType, entityId, before, after, metadata, value, actorId);
    }

    /** A platform operator acted: recorded as actor_type PLATFORM with this id, whatever TenantContext holds. */
    public AuditEntry asPlatformActor(UUID platformUserId) {
        Objects.requireNonNull(platformUserId, "platformUserId");
        return new AuditEntry(action, entityType, entityId, before, after, metadata, ActorType.PLATFORM, platformUserId);
    }
}
