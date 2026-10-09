package com.nexusops.helpdesk;

import com.nexusops.helpdesk.domain.Ticket;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Full-text read models (D12, D13), 'simple' configuration, explicit tenant predicates, bound parameters only. */
@Component
class HelpDeskSearch {

    record ArticlePage(List<UUID> ids, long total) {}

    private final NamedParameterJdbcTemplate jdbc;

    HelpDeskSearch(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<UUID> previousTickets(UUID requesterId, UUID exceptId, int limit) {
        return jdbc.queryForList("""
                select t.id from tickets t
                where t.tenant_id = :tenant and t.requester_id = :requester and t.id <> :except
                order by t.created_at desc, t.id desc limit :limit
                """, params().addValue("requester", requesterId).addValue("except", exceptId).addValue("limit", limit),
                UUID.class);
    }

    /** Open tickets of the same requester whose text matches this ticket's subject words (D12). */
    List<UUID> duplicateCandidates(Ticket ticket, int limit) {
        String query = FullTextTerms.orQuery(ticket.getSubject(), 10);
        if (query.isEmpty()) {
            return List.of();
        }
        return jdbc.queryForList("""
                select t.id from tickets t
                where t.tenant_id = :tenant and t.requester_id = :requester and t.id <> :except
                  and t.status in ('NEW', 'OPEN', 'PENDING') and t.search @@ to_tsquery('simple', :q)
                order by ts_rank(t.search, to_tsquery('simple', :q)) desc, t.created_at desc limit :limit
                """, params().addValue("requester", ticket.getRequesterId()).addValue("except", ticket.getId())
                .addValue("q", query).addValue("limit", limit), UUID.class);
    }

    /** Published articles matching any word of the text, best first (D12). */
    List<UUID> suggestedArticles(String text, int limit) {
        String query = FullTextTerms.orQuery(text, 15);
        if (query.isEmpty()) {
            return List.of();
        }
        return jdbc.queryForList("""
                select a.id from kb_articles a
                where a.tenant_id = :tenant and a.status = 'PUBLISHED' and a.search @@ to_tsquery('simple', :q)
                order by ts_rank(a.search, to_tsquery('simple', :q)) desc, a.updated_at desc limit :limit
                """, params().addValue("q", query).addValue("limit", limit), UUID.class);
    }

    /** The article list: web-search syntax (quotes, OR, -) when {@code q} is given, ranked; otherwise newest first. */
    ArticlePage articlePage(String rawQ, List<ArticleStatus> statuses, UUID categoryId, int limit, long offset) {
        MapSqlParameterSource params = params().addValue("statuses", statuses.stream().map(Enum::name).toList());
        StringBuilder where = new StringBuilder("a.tenant_id = :tenant and a.status in (:statuses)");
        if (categoryId != null) {
            where.append(" and a.category_id = :category");
            params.addValue("category", categoryId);
        }
        String q = Text.optional(rawQ, 200, "q");
        String order = "a.updated_at desc, a.id desc";
        if (q != null) {
            where.append(" and a.search @@ websearch_to_tsquery('simple', :q)");
            params.addValue("q", q);
            order = "ts_rank(a.search, websearch_to_tsquery('simple', :q)) desc, " + order;
        }
        Long total = jdbc.queryForObject("select count(*) from kb_articles a where " + where, params, Long.class);
        params.addValue("limit", limit).addValue("offset", offset);
        List<UUID> ids = jdbc.queryForList("select a.id from kb_articles a where " + where + " order by " + order
                + " limit :limit offset :offset", params, UUID.class);
        return new ArticlePage(ids, total == null ? 0 : total);
    }

    private static MapSqlParameterSource params() {
        return new MapSqlParameterSource("tenant", TenantContext.requireTenantId());
    }
}
