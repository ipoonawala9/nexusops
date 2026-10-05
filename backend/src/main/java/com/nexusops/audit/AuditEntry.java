package com.nexusops.audit;

import java.util.Map;

/**
 * One auditable action. Actor and tenant come from TenantContext; request metadata from the
 * current HTTP request. Keys that look secret are dropped from before/after/metadata.
 */
public record AuditEntry(
        String action,
        String entityType,
        String entityId,
        Map<String, Object> before,
        Map<String, Object> after,
        Map<String, Object> metadata,
        ActorType actorType) {

    public static AuditEntry of(String action, String entityType, Object entityId) {
        return new AuditEntry(action, entityType, entityId == null ? null : entityId.toString(), null, null, null, null);
    }

    public AuditEntry withBefore(Map<String, Object> value) {
        return new AuditEntry(action, entityType, entityId, value, after, metadata, actorType);
    }

    public AuditEntry withAfter(Map<String, Object> value) {
        return new AuditEntry(action, entityType, entityId, before, value, metadata, actorType);
    }

    public AuditEntry withMetadata(Map<String, Object> value) {
        return new AuditEntry(action, entityType, entityId, before, after, value, actorType);
    }

    public AuditEntry asActor(ActorType value) {
        return new AuditEntry(action, entityType, entityId, before, after, metadata, value);
    }
}
