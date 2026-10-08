package com.nexusops.crm;

import com.nexusops.crm.DashboardView.ClosedStats;
import com.nexusops.crm.DashboardView.LeadStats;
import com.nexusops.crm.DashboardView.PipelineStats;
import com.nexusops.crm.DashboardView.StageStats;
import com.nexusops.crm.domain.Opportunity;
import com.nexusops.crm.domain.OpportunityRepository;
import com.nexusops.crm.domain.PipelineStage;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.security.CurrentAuthorities;
import com.nexusops.tenancy.TenantDirectory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The CRM dashboard (D14). Periods are in the workspace time zone; totals are per currency. */
@Service
public class DashboardService {

    private static final int CLOSING_SOON_DAYS = 30;
    private static final int CLOSING_SOON_LIMIT = 10;

    private final NamedParameterJdbcTemplate jdbc;
    private final PipelineService pipeline;
    private final OpportunityService opportunities;
    private final OpportunityRepository opportunityRows;
    private final TenantDirectory tenants;

    DashboardService(NamedParameterJdbcTemplate jdbc, PipelineService pipeline, OpportunityService opportunities,
            OpportunityRepository opportunityRows, TenantDirectory tenants) {
        this.jdbc = jdbc;
        this.pipeline = pipeline;
        this.opportunities = opportunities;
        this.opportunityRows = opportunityRows;
        this.tenants = tenants;
    }

    @Transactional(readOnly = true)
    public DashboardView dashboard(String owner) {
        UUID tenant = TenantContext.requireTenantId();
        UUID ownerId = OpportunityService.meOrAll(owner);
        Instant now = Instant.now();
        ZoneId zone = ZoneId.of(tenants.currentSettings().timezone());
        LeadStats leads = CurrentAuthorities.has(CrmPermissions.LEAD_READ) ? leads(tenant, ownerId, now) : null;
        PipelineStats pipelineStats = CurrentAuthorities.has(CrmPermissions.OPPORTUNITY_READ)
                ? pipeline(tenant, ownerId, now, zone) : null;
        return new DashboardView(leads, pipelineStats);
    }

    private LeadStats leads(UUID tenant, UUID ownerId, Instant now) {
        String mine = ownerId == null ? "" : " and owner_id = :owner";
        MapSqlParameterSource p = params(tenant, ownerId)
                .addValue("since30", Timestamp.from(now.minus(Duration.ofDays(30))))
                .addValue("since90", Timestamp.from(now.minus(Duration.ofDays(90))));
        Map<LeadStatus, Long> open = new EnumMap<>(LeadStatus.class);
        open.put(LeadStatus.NEW, 0L);
        open.put(LeadStatus.CONTACTED, 0L);
        open.put(LeadStatus.QUALIFIED, 0L);
        jdbc.query("select status, count(*) as n from leads where tenant_id = :tenant"
                + " and status in ('NEW', 'CONTACTED', 'QUALIFIED')" + mine + " group by status", p,
                rs -> {
                    open.put(LeadStatus.valueOf(rs.getString("status")), rs.getLong("n"));
                });
        Map<String, Object> row = jdbc.queryForMap("""
                select count(*) filter (where created_at >= :since30) as created,
                       count(*) filter (where status = 'CONVERTED' and converted_at >= :since90) as converted,
                       count(*) filter (where status = 'DISQUALIFIED' and disqualified_at >= :since90) as disqualified
                from leads where tenant_id = :tenant""" + mine, p);
        long converted = ((Number) row.get("converted")).longValue();
        long disqualified = ((Number) row.get("disqualified")).longValue();
        BigDecimal rate = converted + disqualified == 0 ? null
                : BigDecimal.valueOf(converted).divide(BigDecimal.valueOf(converted + disqualified), 4, RoundingMode.HALF_UP);
        return new LeadStats(open, ((Number) row.get("created")).longValue(), converted, disqualified, rate);
    }

    private PipelineStats pipeline(UUID tenant, UUID ownerId, Instant now, ZoneId zone) {
        String mine = ownerId == null ? "" : " and o.owner_id = :owner";
        MapSqlParameterSource p = params(tenant, ownerId)
                .addValue("monthStart", Timestamp.from(DashboardPeriods.monthStart(now, zone)));
        List<PipelineStage> stages = pipeline.ordered();
        Map<UUID, long[]> counts = new HashMap<>();
        Map<UUID, MoneySums> sums = new HashMap<>();
        Map<UUID, MoneySums> weighted = new HashMap<>();
        Map<UUID, Integer> probability = stages.stream()
                .collect(Collectors.toMap(PipelineStage::getId, PipelineStage::getProbability));
        jdbc.query("""
                select o.stage_id, o.currency, count(*) as n, sum(o.amount) as total
                from opportunities o join pipeline_stages s on s.id = o.stage_id and s.tenant_id = o.tenant_id
                where o.tenant_id = :tenant and s.kind = 'OPEN'""" + mine + " group by o.stage_id, o.currency", p,
                rs -> {
                    UUID stage = rs.getObject("stage_id", UUID.class);
                    counts.computeIfAbsent(stage, k -> new long[1])[0] += rs.getLong("n");
                    BigDecimal total = rs.getBigDecimal("total");
                    String currency = rs.getString("currency");
                    sums.computeIfAbsent(stage, k -> new MoneySums()).add(currency, total);
                    weighted.computeIfAbsent(stage, k -> new MoneySums()).add(currency, total == null ? null
                            : total.multiply(BigDecimal.valueOf(probability.get(stage))).movePointLeft(2));
                });
        List<StageStats> stageStats = new ArrayList<>();
        for (PipelineStage s : stages) {
            if (s.getKind() == StageKind.OPEN) {
                stageStats.add(new StageStats(PipelineService.view(s), counts.getOrDefault(s.getId(), new long[1])[0],
                        sums.getOrDefault(s.getId(), new MoneySums()).totals(),
                        weighted.getOrDefault(s.getId(), new MoneySums()).totals()));
            }
        }
        long[] wonCount = new long[1];
        long[] lostCount = new long[1];
        MoneySums wonSums = new MoneySums();
        jdbc.query("""
                select s.kind, o.currency, count(*) as n, sum(o.amount) as total
                from opportunities o join pipeline_stages s on s.id = o.stage_id and s.tenant_id = o.tenant_id
                where o.tenant_id = :tenant and s.kind in ('WON', 'LOST') and o.closed_at >= :monthStart""" + mine
                + " group by s.kind, o.currency", p,
                rs -> {
                    if (rs.getString("kind").equals("WON")) {
                        wonCount[0] += rs.getLong("n");
                        wonSums.add(rs.getString("currency"), rs.getBigDecimal("total"));
                    } else {
                        lostCount[0] += rs.getLong("n");
                    }
                });
        return new PipelineStats(stageStats, new ClosedStats(wonCount[0], wonSums.totals()),
                new ClosedStats(lostCount[0], List.of()), closingSoon(stages, ownerId, now, zone));
    }

    private List<OpportunitySummary> closingSoon(List<PipelineStage> stages, UUID ownerId, Instant now, ZoneId zone) {
        Set<UUID> open = stages.stream().filter(s -> s.getKind() == StageKind.OPEN).map(PipelineStage::getId)
                .collect(Collectors.toSet());
        LocalDate today = DashboardPeriods.today(now, zone);
        LocalDate until = today.plusDays(CLOSING_SOON_DAYS);
        Specification<Opportunity> spec = (root, cq, cb) -> cb.and(root.get("stageId").in(open),
                cb.between(root.get("expectedCloseOn"), today, until));
        if (ownerId != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("ownerId"), ownerId));
        }
        List<Opportunity> soon = opportunityRows.findAll(spec, PageRequest.of(0, CLOSING_SOON_LIMIT,
                Sort.by(Sort.Order.asc("expectedCloseOn"), Sort.Order.asc("id")))).getContent();
        return opportunities.summaries(soon);
    }

    private static MapSqlParameterSource params(UUID tenant, UUID ownerId) {
        return new MapSqlParameterSource("tenant", tenant).addValue("owner", ownerId);
    }
}
