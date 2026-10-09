package com.nexusops.helpdesk;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.helpdesk.domain.SlaPolicy;
import com.nexusops.helpdesk.domain.SlaPolicyRepository;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.web.ApiProblem;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One SLA policy per priority (D8). Targets are minutes: 1 to 60 days, resolution not shorter than first response. */
@Service
public class SlaPolicyService {

    static final int MAX_MINUTES = 60 * 24 * 60;
    static final Map<Priority, SlaTargets> DEFAULTS = new EnumMap<>(Map.of(
            Priority.URGENT, new SlaTargets(60, 240), Priority.HIGH, new SlaTargets(240, 1440),
            Priority.NORMAL, new SlaTargets(480, 2880), Priority.LOW, new SlaTargets(1440, 7200)));
    private static final String LOCK = "helpdesk-sla";

    private final SlaPolicyRepository policies;
    private final TenantLocks locks;
    private final AuditService audit;

    SlaPolicyService(SlaPolicyRepository policies, TenantLocks locks, AuditService audit) {
        this.policies = policies;
        this.locks = locks;
        this.audit = audit;
    }

    /** Most urgent first. */
    @Transactional(readOnly = true)
    public List<SlaPolicyView> list() {
        TenantContext.requireTenantId();
        return policies.findAll().stream()
                .sorted(Comparator.comparing(SlaPolicy::getPriority).reversed())
                .map(SlaPolicyService::view).toList();
    }

    @Transactional
    public SlaPolicyView update(Priority priority, SlaPolicyCommand command, Long version) {
        TenantContext.requireTenantId();
        int first = minutes(command.firstResponseMinutes(), "firstResponseMinutes");
        int resolution = minutes(command.resolutionMinutes(), "resolutionMinutes");
        if (resolution < first) {
            throw ApiProblem.badRequestField("resolutionMinutes", "Allow at least as long to resolve as to respond.");
        }
        if (version == null) {
            throw ApiProblem.badRequestField("version", "Reload the record and try again.");
        }
        SlaPolicy policy = policies.findByPriority(priority).orElseThrow(() -> ApiProblem.notFound("Record not found."));
        if (policy.getVersion() != version) {
            throw ApiProblem.conflict("This record was changed by someone else. Reload and try again.");
        }
        SlaPolicyView before = view(policy);
        policy.apply(first, resolution);
        policies.flush();
        audit.record(AuditEntry.of("SlaPolicyUpdated", "SlaPolicy", policy.getId())
                .withBefore(Map.of("firstResponseMinutes", before.firstResponseMinutes(),
                        "resolutionMinutes", before.resolutionMinutes()))
                .withAfter(Map.of("priority", priority.name(), "firstResponseMinutes", first,
                        "resolutionMinutes", resolution)));
        return view(policy);
    }

    /** The targets a ticket of this priority gets; the defaults if a workspace somehow lacks the row. */
    @Transactional(readOnly = true)
    SlaTargets targets(Priority priority) {
        return policies.findByPriority(priority)
                .map(p -> new SlaTargets(p.getFirstResponseMinutes(), p.getResolutionMinutes()))
                .orElse(DEFAULTS.get(priority));
    }

    @Transactional
    public void seedDefaults() {
        TenantContext.requireTenantId();
        locks.lock(LOCK);
        if (policies.count() > 0) {
            return;
        }
        DEFAULTS.forEach((priority, t) -> policies.save(
                new SlaPolicy(Ids.newId(), priority, t.firstResponseMinutes(), t.resolutionMinutes())));
        policies.flush();
    }

    private static int minutes(Integer value, String field) {
        if (value == null || value < 1) {
            throw ApiProblem.badRequestField(field, "Enter a number of minutes greater than 0.");
        }
        if (value > MAX_MINUTES) {
            throw ApiProblem.badRequestField(field, "Use at most 60 days.");
        }
        return value;
    }

    private static SlaPolicyView view(SlaPolicy p) {
        return new SlaPolicyView(p.getPriority(), p.getFirstResponseMinutes(), p.getResolutionMinutes(), p.getVersion());
    }
}
