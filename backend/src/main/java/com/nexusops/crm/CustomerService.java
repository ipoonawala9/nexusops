package com.nexusops.crm;

import com.nexusops.directory.PartyQuery;
import com.nexusops.directory.PartyRoleType;
import com.nexusops.directory.PartyService;
import com.nexusops.directory.PartySummary;
import com.nexusops.directory.PartyView;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.web.PageResponse;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Customers are directory parties with an active CUSTOMER role (D7); this adds their sales figures. */
@Service
public class CustomerService {

    private static final class Stats {
        long open;
        long won;
        long lost;
        final MoneySums openValue = new MoneySums();
        final MoneySums weighted = new MoneySums();
        final MoneySums wonValue = new MoneySums();
    }

    private final PartyService parties;
    private final NamedParameterJdbcTemplate jdbc;

    CustomerService(PartyService parties, NamedParameterJdbcTemplate jdbc) {
        this.parties = parties;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerRow> list(String q, Integer page, Integer size) {
        PageResponse<PartySummary> found = parties.list(new PartyQuery(q, null, PartyRoleType.CUSTOMER, null, false),
                page, size);
        Map<UUID, Stats> stats = stats(found.items().stream().map(PartySummary::id).toList());
        return new PageResponse<>(found.items().stream().map(p -> {
            Stats s = stats.getOrDefault(p.id(), new Stats());
            return new CustomerRow(p, s.open, s.openValue.totals(), s.won, s.wonValue.totals());
        }).toList(), found.page(), found.size(), found.total());
    }

    @Transactional(readOnly = true)
    public CustomerSummary summary(UUID partyId) {
        PartyView party = parties.get(partyId);
        Stats s = stats(List.of(partyId)).getOrDefault(partyId, new Stats());
        Long leads = jdbc.queryForObject("""
                select count(*) from leads
                where tenant_id = :tenant and (converted_person_id = :party or converted_organization_id = :party)
                """, new MapSqlParameterSource("tenant", TenantContext.requireTenantId()).addValue("party", partyId),
                Long.class);
        return new CustomerSummary(party, s.open, s.openValue.totals(), s.weighted.totals(), s.won, s.wonValue.totals(),
                s.lost, leads == null ? 0 : leads);
    }

    private Map<UUID, Stats> stats(Collection<UUID> accountIds) {
        Map<UUID, Stats> result = new HashMap<>();
        if (accountIds.isEmpty()) {
            return result;
        }
        jdbc.query("""
                select o.account_id, s.kind, s.probability, o.currency, count(*) as n, sum(o.amount) as total
                from opportunities o join pipeline_stages s on s.id = o.stage_id and s.tenant_id = o.tenant_id
                where o.tenant_id = :tenant and o.account_id in (:ids)
                group by o.account_id, s.kind, s.probability, o.currency
                """, new MapSqlParameterSource("tenant", TenantContext.requireTenantId()).addValue("ids", accountIds),
                rs -> {
                    Stats s = result.computeIfAbsent(rs.getObject("account_id", UUID.class), k -> new Stats());
                    long n = rs.getLong("n");
                    String currency = rs.getString("currency");
                    BigDecimal total = rs.getBigDecimal("total");
                    switch (StageKind.valueOf(rs.getString("kind"))) {
                        case OPEN -> {
                            s.open += n;
                            s.openValue.add(currency, total);
                            s.weighted.add(currency, total == null ? null
                                    : total.multiply(BigDecimal.valueOf(rs.getInt("probability"))).movePointLeft(2));
                        }
                        case WON -> {
                            s.won += n;
                            s.wonValue.add(currency, total);
                        }
                        case LOST -> s.lost += n;
                    }
                });
        return result;
    }
}
