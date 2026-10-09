package com.nexusops.helpdesk;

import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.web.ApiProblem;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SQL read models for HelpDesk. The SLA fragments mirror SlaClock (D8) — keep the two in step: breached = a running
 * clock past its due time, or a pause that began after the due time; at risk = running, not breached, and less than
 * 25 % of the target left.
 */
@Component
class HelpDeskQueries {

    static final String OPEN_STATUSES = "t.status in ('NEW', 'OPEN', 'PENDING')";

    static final String FIRST_RESPONSE_BREACHED = "(t.first_responded_at is null and t.first_response_due_at < now())";
    static final String RESOLUTION_BREACHED = "((t.paused_at is null and t.resolution_due_at < now()) "
            + "or (t.paused_at is not null and t.paused_at > t.resolution_due_at))";
    static final String BREACHED = "(" + OPEN_STATUSES + " and (" + FIRST_RESPONSE_BREACHED + " or "
            + RESOLUTION_BREACHED + "))";
    static final String AT_RISK = "(" + OPEN_STATUSES + " and not " + BREACHED + " and ("
            + "(t.first_responded_at is null and t.first_response_due_at - now() < (t.first_response_due_at - t.created_at) * 0.25)"
            + " or (t.paused_at is null and t.resolution_due_at - now() < (t.resolution_due_at - t.resolution_clock_started_at"
            + " - make_interval(secs => t.paused_seconds)) * 0.25)))";

    record Page(List<UUID> ids, long total) {}

    private final NamedParameterJdbcTemplate jdbc;

    HelpDeskQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Page ticketPage(TicketQuery query, UUID currentUser, int limit, long offset) {
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", TenantContext.requireTenantId());
        String where = where(query, currentUser, params);
        Long total = jdbc.queryForObject("select count(*) from tickets t where " + where, params, Long.class);
        params.addValue("limit", limit).addValue("offset", offset);
        List<UUID> ids = jdbc.queryForList("select t.id from tickets t where " + where
                + " order by t.created_at desc, t.id desc limit :limit offset :offset", params, UUID.class);
        return new Page(ids, total == null ? 0 : total);
    }

    private static String where(TicketQuery q, UUID currentUser, MapSqlParameterSource params) {
        StringBuilder w = new StringBuilder("t.tenant_id = :tenant");
        List<TicketStatus> statuses = q.statuses() == null || q.statuses().isEmpty()
                ? List.of(TicketStatus.NEW, TicketStatus.OPEN, TicketStatus.PENDING) : q.statuses();
        w.append(" and t.status in (:statuses)");
        params.addValue("statuses", statuses.stream().map(Enum::name).toList());
        if (q.priority() != null) {
            w.append(" and t.priority = :priority");
            params.addValue("priority", q.priority().name());
        }
        if (q.assignee() != null && !q.assignee().isBlank()) {
            switch (q.assignee()) {
                case "unassigned" -> w.append(" and t.assignee_id is null");
                case "me" -> {
                    w.append(" and t.assignee_id = :assignee");
                    params.addValue("assignee", currentUser);
                }
                default -> {
                    w.append(" and t.assignee_id = :assignee");
                    params.addValue("assignee", uuid(q.assignee(), "assignee"));
                }
            }
        }
        if (q.categoryId() != null) {
            w.append(" and t.category_id = :category");
            params.addValue("category", q.categoryId());
        }
        if (q.requesterId() != null) {
            w.append(" and t.requester_id = :requester");
            params.addValue("requester", q.requesterId());
        }
        if (q.productId() != null) {
            w.append(" and t.product_id = :product");
            params.addValue("product", q.productId());
        }
        String text = Text.optional(q.q(), 100, "q");
        if (text != null) {
            w.append(" and (lower(t.number) like :pattern escape '\\' or lower(t.subject) like :pattern escape '\\')");
            params.addValue("pattern", Text.containsPattern(text));
        }
        if (q.sla() != null && !q.sla().isBlank()) {
            switch (q.sla()) {
                case "breached" -> w.append(" and ").append(BREACHED);
                case "at_risk" -> w.append(" and ").append(AT_RISK);
                default -> throw ApiProblem.badRequestField("sla", "Use breached or at_risk.");
            }
        }
        return w.toString();
    }

    private static UUID uuid(String raw, String field) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw ApiProblem.badRequestField(field, "Use a member id, me or unassigned.");
        }
    }
}
