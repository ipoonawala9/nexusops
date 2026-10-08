package com.nexusops.crm;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.crm.domain.PipelineStage;
import com.nexusops.crm.domain.PipelineStageRepository;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.web.ApiProblem;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The workspace's single pipeline (D5): 1–12 open stages, then one Won and one Lost stage. */
@Service
public class PipelineService {

    static final String NOT_FOUND = "Record not found.";
    static final String STALE = "This record was changed by someone else. Reload and try again.";
    static final String NAME_TAKEN = "Another stage already has this name.";
    static final String IN_USE = "Move this stage's opportunities first.";
    static final String LAST_OPEN = "The pipeline needs at least one open stage.";
    static final String TOO_MANY = "A pipeline can have at most 12 open stages.";
    static final String FIXED = "The Won and Lost stages can be renamed but not deleted or moved.";
    static final String UNKNOWN_STAGE = "Choose a stage of this pipeline.";
    static final int MAX_OPEN = 12;
    private static final String LOCK = "pipeline";
    private static final Comparator<PipelineStage> BOARD_ORDER = Comparator.comparing(PipelineStage::getKind)
            .thenComparingInt(PipelineStage::getPosition).thenComparing(PipelineStage::getId);
    private static final List<Object[]> DEFAULTS = List.of(new Object[] {"Prospecting", 10, StageKind.OPEN},
            new Object[] {"Qualification", 25, StageKind.OPEN}, new Object[] {"Proposal", 50, StageKind.OPEN},
            new Object[] {"Negotiation", 75, StageKind.OPEN}, new Object[] {"Won", 100, StageKind.WON},
            new Object[] {"Lost", 0, StageKind.LOST});

    private final PipelineStageRepository stages;
    private final TenantLocks locks;
    private final AuditService audit;

    PipelineService(PipelineStageRepository stages, TenantLocks locks, AuditService audit) {
        this.stages = stages;
        this.locks = locks;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<StageView> stages() {
        return ordered().stream().map(PipelineService::view).toList();
    }

    @Transactional
    public StageView create(StageCommand command) {
        TenantContext.requireTenantId();
        String name = name(command.name());
        int probability = probability(command.probability());
        locks.lock(LOCK);
        List<PipelineStage> open = ordered().stream().filter(s -> s.getKind() == StageKind.OPEN).toList();
        if (open.size() >= MAX_OPEN) {
            throw ApiProblem.conflict(TOO_MANY);
        }
        if (stages.existsByNameKey(key(name))) {
            throw ApiProblem.conflictField("name", NAME_TAKEN);
        }
        int position = open.isEmpty() ? 0 : open.getLast().getPosition() + 1;
        PipelineStage stage = new PipelineStage(Ids.newId(), name, key(name), position, probability, StageKind.OPEN);
        save(stage);
        audit.record(AuditEntry.of("PipelineStageCreated", "PipelineStage", stage.getId())
                .withAfter(Map.of("name", name, "probability", probability)));
        return view(stage);
    }

    @Transactional
    public StageView update(UUID id, StageCommand command, Long version) {
        PipelineStage stage = find(id);
        if (version == null) {
            throw ApiProblem.badRequestField("version", "Reload the record and try again.");
        }
        if (stage.getVersion() != version) {
            throw ApiProblem.conflict(STALE);
        }
        String name = name(command.name());
        int probability = probability(command.probability());
        locks.lock(LOCK);
        if (stages.existsByNameKeyAndIdNot(key(name), id)) {
            throw ApiProblem.conflictField("name", NAME_TAKEN);
        }
        Map<String, Object> before = Map.of("name", stage.getName(), "probability", stage.getProbability());
        stage.rename(name, key(name), probability);
        save(stage);
        audit.record(AuditEntry.of("PipelineStageUpdated", "PipelineStage", id).withBefore(before)
                .withAfter(Map.of("name", name, "probability", probability)));
        return view(stage);
    }

    @Transactional
    public void delete(UUID id) {
        PipelineStage stage = find(id);
        if (stage.getKind() != StageKind.OPEN) {
            throw ApiProblem.conflict(FIXED);
        }
        locks.lock(LOCK);
        long open = ordered().stream().filter(s -> s.getKind() == StageKind.OPEN).count();
        if (open <= 1) {
            throw ApiProblem.conflict(LAST_OPEN);
        }
        try {
            stages.delete(stage);
            stages.flush();
        } catch (DataIntegrityViolationException referenced) {
            throw ApiProblem.conflict(IN_USE);
        }
        audit.record(AuditEntry.of("PipelineStageDeleted", "PipelineStage", id)
                .withBefore(Map.of("name", stage.getName())));
    }

    /** {@code openStageIds} must be exactly the open stages, in their new order; Won and Lost stay last. */
    @Transactional
    public List<StageView> reorder(List<UUID> openStageIds) {
        TenantContext.requireTenantId();
        locks.lock(LOCK);
        List<PipelineStage> open = ordered().stream().filter(s -> s.getKind() == StageKind.OPEN).toList();
        List<UUID> requested = openStageIds == null ? List.of() : openStageIds;
        if (requested.size() != open.size() || new HashSet<>(requested).size() != requested.size()
                || !new HashSet<>(requested).equals(new HashSet<>(open.stream().map(PipelineStage::getId).toList()))) {
            throw ApiProblem.badRequestField("stageIds", "List every open stage exactly once.");
        }
        Map<UUID, PipelineStage> byId = new HashMap<>();
        open.forEach(s -> byId.put(s.getId(), s));
        for (int i = 0; i < requested.size(); i++) {
            byId.get(requested.get(i)).moveTo(i);
        }
        stages.flush();
        audit.record(AuditEntry.of("PipelineStagesReordered", "Pipeline", TenantContext.requireTenantId())
                .withAfter(Map.of("stageIds", requested.stream().map(UUID::toString).toList())));
        return stages();
    }

    /** Idempotent: a workspace that has any stage keeps its pipeline. */
    @Transactional
    public void seedDefaults() {
        TenantContext.requireTenantId();
        locks.lock(LOCK);
        if (stages.count() > 0) {
            return;
        }
        int position = 0;
        for (Object[] row : DEFAULTS) {
            String name = (String) row[0];
            StageKind kind = (StageKind) row[2];
            stages.save(new PipelineStage(Ids.newId(), name, key(name), kind == StageKind.OPEN ? position++ : 0,
                    (Integer) row[1], kind));
        }
        stages.flush();
    }

    List<PipelineStage> ordered() {
        TenantContext.requireTenantId();
        return stages.findAll().stream().sorted(BOARD_ORDER).toList();
    }

    /** A stage referenced from a request body: unknown (or another tenant's) is a 400 on {@code field}. */
    PipelineStage require(UUID id, String field) {
        TenantContext.requireTenantId();
        if (id == null) {
            throw ApiProblem.badRequestField(field, UNKNOWN_STAGE);
        }
        return stages.findById(id).orElseThrow(() -> ApiProblem.badRequestField(field, UNKNOWN_STAGE));
    }

    PipelineStage firstOpen() {
        return ordered().stream().filter(s -> s.getKind() == StageKind.OPEN).findFirst()
                .orElseThrow(() -> new IllegalStateException("A pipeline always has an open stage"));
    }

    static StageRef ref(PipelineStage s) {
        return new StageRef(s.getId(), s.getName(), s.getKind(), s.getProbability());
    }

    static StageView view(PipelineStage s) {
        return new StageView(s.getId(), s.getName(), s.getProbability(), s.getKind(), s.getPosition(), s.getVersion());
    }

    private PipelineStage find(UUID id) {
        TenantContext.requireTenantId();
        return stages.findById(id).orElseThrow(() -> ApiProblem.notFound(NOT_FOUND));
    }

    private void save(PipelineStage stage) {
        try {
            stages.saveAndFlush(stage);
        } catch (DataIntegrityViolationException race) {
            throw ApiProblem.conflictField("name", NAME_TAKEN);
        }
    }

    private static String name(String raw) {
        return Text.required(raw, 60, "name").replaceAll("\\s+", " ");
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static int probability(Integer raw) {
        if (raw == null || raw < 0 || raw > 100) {
            throw ApiProblem.badRequestField("probability", "Enter a probability from 0 to 100.");
        }
        return raw;
    }
}
