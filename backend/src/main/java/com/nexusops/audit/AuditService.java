package com.nexusops.audit;

import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.web.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/**
 * Append-only audit log (ADR-0005). Written with plain JDBC (not JPA) because pre-tenant events
 * legitimately have no tenant, which Hibernate's @TenantId would refuse.
 */
@Service
public class AuditService {

    private static final Pattern SENSITIVE_KEY = Pattern.compile("(?i).*(password|token|secret|hash|cookie|jwt).*");
    private static final int MAX_USER_AGENT = 512;

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    AuditService(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Records in the caller's transaction: the audit row exists iff the business change commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEntry entry) {
        insert(entry);
    }

    /** Records in its own transaction, for events that must persist even if the caller fails (e.g. failed login). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependently(AuditEntry entry) {
        insert(entry);
    }

    private void insert(AuditEntry entry) {
        UUID tenantId = TenantContext.tenantId().orElse(null);
        UUID actorId = entry.actorId() != null ? entry.actorId() : TenantContext.userId().orElse(null);
        ActorType actorType = entry.actorType() != null ? entry.actorType()
                : actorId != null ? ActorType.USER : ActorType.ANONYMOUS;
        HttpServletRequest request = currentRequest();
        String requestId = RequestIds.current();

        jdbc.update("""
                insert into audit_events (id, tenant_id, actor_type, actor_id, action, entity_type, entity_id,
                    occurred_at, ip, user_agent, request_id, correlation_id, before, after, metadata)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb)
                """,
                Ids.newId(), tenantId, actorType.name(), actorId, entry.action(), entry.entityType(), entry.entityId(),
                Timestamp.from(Instant.now()),
                request == null ? null : request.getRemoteAddr(),
                request == null ? null : truncate(request.getHeader("User-Agent")),
                "none".equals(requestId) ? null : requestId,
                "none".equals(requestId) ? null : RequestIds.correlationId(),
                toJson(entry.before()), toJson(entry.after()), toJson(entry.metadata()));
    }

    private String toJson(Map<String, Object> values) {
        if (values == null) {
            return null;
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (!SENSITIVE_KEY.matcher(key).matches()) {
                safe.put(key, value);
            }
        });
        return json.writeValueAsString(safe);
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest() : null;
    }

    private static String truncate(String value) {
        return value == null || value.length() <= MAX_USER_AGENT ? value : value.substring(0, MAX_USER_AGENT);
    }
}
