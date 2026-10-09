package com.nexusops.helpdesk;

import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.web.ApiProblem;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SQL read models for HelpDesk. The SLA fragments mirror SlaClock (D8) — keep the two in step: breached = a first
 * response that is overdue or came late, a running clock past its due time, or a pause that began after the due time;
 * at risk = running, not breached, and less than 25 % of the target left.
 */
@Component
class HelpDeskQueries {

    static final String OPEN_STATUSES = "t.status in ('NEW', 'OPEN', 'PENDING')";

    static final String FIRST_RESPONSE_BREACHED = "((t.first_responded_at is null and t.first_response_due_at < now()) "
            + "or (t.first_responded_at is not null and t.first_responded_at > t.first_response_due_at))";
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

    DashboardView dashboard() {
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", TenantContext.requireTenantId());
        Map<TicketStatus, Long> byStatus = new EnumMap<>(TicketStatus.class);
        for (TicketStatus s : List.of(TicketStatus.NEW, TicketStatus.OPEN, TicketStatus.PENDING)) {
            byStatus.put(s, 0L);
        }
        Map<Priority, Long> byPriority = new EnumMap<>(Priority.class);
        for (Priority p : Priority.values()) {
            byPriority.put(p, 0L);
        }
        jdbc.query("select t.status, t.priority, count(*) as n from tickets t where t.tenant_id = :tenant and "
                + OPEN_STATUSES + " group by t.status, t.priority", params, rs -> {
                    long n = rs.getLong("n");
                    byStatus.merge(TicketStatus.valueOf(rs.getString("status")), n, Long::sum);
                    byPriority.merge(Priority.valueOf(rs.getString("priority")), n, Long::sum);
                });
        Map<String, Object> open = jdbc.queryForMap("select "
                + "count(*) filter (where t.assignee_id is null) as unassigned, "
                + "count(*) filter (where " + BREACHED + ") as breached, "
                + "count(*) filter (where " + AT_RISK + ") as at_risk "
                + "from tickets t where t.tenant_id = :tenant and " + OPEN_STATUSES, params);
        // a target that has passed without being met counts as a miss, so an overdue unanswered ticket lowers the rate
        Map<String, Object> w = jdbc.queryForMap("""
                select count(*) as created,
                       count(*) filter (where t.resolved_at is not null) as resolved,
                       avg(extract(epoch from t.first_responded_at - t.created_at) / 60) as avg_first,
                       percentile_cont(0.5) within group (order by extract(epoch from t.first_responded_at - t.created_at) / 60)
                           filter (where t.first_responded_at is not null) as median_first,
                       avg(extract(epoch from t.resolved_at - t.created_at) / 60) as avg_resolution,
                       avg(case when t.first_responded_at <= t.first_response_due_at then 1.0 else 0.0 end)
                           filter (where t.first_responded_at is not null
                                      or t.first_response_due_at < now()) as first_met,
                       avg(case when t.resolved_at <= t.resolution_due_at then 1.0 else 0.0 end)
                           filter (where t.resolved_at is not null or %s) as resolution_met,
                       avg(case when t.reopen_count > 0 then 1.0 else 0.0 end)
                           filter (where t.resolved_at is not null or t.reopen_count > 0) as reopen_rate
                from tickets t
                where t.tenant_id = :tenant and t.created_at >= now() - interval '30 days'
                """.formatted(OPEN_STATUSES + " and " + RESOLUTION_BREACHED), params);
        return new DashboardView(byStatus, byPriority, number(open.get("unassigned")), number(open.get("breached")),
                number(open.get("at_risk")), new DashboardView.Last30Days(number(w.get("created")),
                        number(w.get("resolved")), decimal(w.get("avg_first")), decimal(w.get("median_first")),
                        decimal(w.get("avg_resolution")), decimal(w.get("first_met")), decimal(w.get("resolution_met")),
                        decimal(w.get("reopen_rate"))));
    }

    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    /** Rounded to 2 decimals; null stays null (nothing to measure). */
    private static Double decimal(Object value) {
        return value == null ? null : Math.round(((Number) value).doubleValue() * 100) / 100.0;
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
