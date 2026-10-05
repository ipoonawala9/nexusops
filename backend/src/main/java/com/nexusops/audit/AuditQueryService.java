package com.nexusops.audit;

import com.nexusops.shared.TenantContext;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.shared.web.PageResponse;
import com.nexusops.shared.web.Paging;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read side of the append-only audit log. RLS scopes rows; the explicit tenant predicate is belt and braces. */
@Service
public class AuditQueryService {

    private static final Pattern ACTION = Pattern.compile("[A-Za-z]{1,60}");
    private static final Pattern ENTITY_TYPE = Pattern.compile("[A-Za-z]{1,60}");

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    AuditQueryService(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditEventView> search(AuditQuery query) {
        UUID tenantId = TenantContext.requireTenantId();
        var pageable = Paging.of(query.page(), query.size());
        List<String> where = new ArrayList<>(List.of("tenant_id = ?"));
        List<Object> args = new ArrayList<>(List.of(tenantId));
        if (notBlank(query.action())) {
            require(ACTION, query.action(), "action");
            where.add("action = ?");
            args.add(query.action());
        }
        if (notBlank(query.entityType())) {
            require(ENTITY_TYPE, query.entityType(), "entityType");
            where.add("entity_type = ?");
            args.add(query.entityType());
        }
        if (query.actorId() != null) {
            where.add("actor_id = ?");
            args.add(query.actorId());
        }
        Instant from = parseInstant(query.from(), "from");
        Instant to = parseInstant(query.to(), "to");
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiProblem.badRequestField("from", "'from' must not be after 'to'.");
        }
        if (from != null) {
            where.add("occurred_at >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            where.add("occurred_at <= ?");
            args.add(Timestamp.from(to));
        }
        String condition = String.join(" and ", where);
        Long total = jdbc.queryForObject("select count(*) from audit_events where " + condition, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageable.getPageSize());
        pageArgs.add(pageable.getOffset());
        List<AuditEventView> items = jdbc.query("""
                select id, occurred_at, actor_type, actor_id, action, entity_type, entity_id, ip, user_agent,
                       request_id, correlation_id, before::text as before, after::text as after, metadata::text as metadata
                from audit_events where %s
                order by occurred_at desc, id desc
                limit ? offset ?""".formatted(condition), this::row, pageArgs.toArray());
        return new PageResponse<>(items, pageable.getPageNumber(), pageable.getPageSize(), total == null ? 0 : total);
    }

    private AuditEventView row(ResultSet rs, int n) throws SQLException {
        return new AuditEventView(rs.getObject("id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("actor_type"), rs.getObject("actor_id", UUID.class), rs.getString("action"),
                rs.getString("entity_type"), rs.getString("entity_id"), rs.getString("ip"), rs.getString("user_agent"),
                rs.getString("request_id"), rs.getString("correlation_id"), node(rs.getString("before")),
                node(rs.getString("after")), node(rs.getString("metadata")));
    }

    private JsonNode node(String value) {
        return value == null ? null : json.readTree(value);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(Pattern pattern, String value, String field) {
        if (!pattern.matcher(value).matches()) {
            throw ApiProblem.badRequestField(field, "Invalid value.");
        }
    }

    private static Instant parseInstant(String value, String field) {
        if (!notBlank(value)) return null;
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw ApiProblem.badRequestField(field, "Use an ISO-8601 instant such as 2026-10-05T09:00:00Z.");
        }
    }
}
