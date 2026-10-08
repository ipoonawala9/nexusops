package com.nexusops.inventory;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.inventory.domain.Warehouse;
import com.nexusops.inventory.domain.WarehouseRepository;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.web.ApiProblem;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Warehouses (D4). Codes are unique ignoring case and never reused; at least one warehouse stays active.
 *
 * <p>Lock rule: anything that writes stock or an order against a warehouse resolves it with {@link #resolve} /
 * {@link #requireActive} inside its own transaction, which takes the warehouse row FOR SHARE until commit.
 * {@link #archive} takes the row FOR UPDATE before checking that the warehouse is unused, so an archive waits for
 * in-flight writers and any later writer sees {@code archived_at}. Stock and open-order checks in
 * {@code requireUnused} therefore cannot race with a concurrent write.
 */
@Service
public class WarehouseService {

    static final String NOT_FOUND = "Record not found.";
    static final String ARCHIVED = "This record is archived.";
    static final String STALE = "This record was changed by someone else. Reload and try again.";
    static final String CODE_TAKEN = "Another warehouse already uses this code.";
    static final String LAST_ACTIVE = "Keep at least one active warehouse.";
    static final String UNKNOWN = "Choose a warehouse in this workspace.";
    static final String OPEN_ORDERS = "Close or move this warehouse's open orders first.";
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,19}$");
    private static final String LOCK = "warehouses";

    private final WarehouseRepository warehouses;
    private final TenantLocks locks;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    WarehouseService(WarehouseRepository warehouses, TenantLocks locks, AuditService audit, JdbcTemplate jdbc) {
        this.warehouses = warehouses;
        this.locks = locks;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<WarehouseView> list(boolean archived) {
        TenantContext.requireTenantId();
        return (archived ? warehouses.findByArchivedAtIsNotNullOrderByCodeKeyAsc()
                : warehouses.findByArchivedAtIsNullOrderByCodeKeyAsc()).stream().map(WarehouseService::view).toList();
    }

    @Transactional
    public WarehouseView create(WarehouseCommand command) {
        TenantContext.requireTenantId();
        String code = code(command.code());
        String name = Text.required(command.name(), 100, "name").replaceAll("\\s+", " ");
        String address = Text.optional(command.address(), 500, "address");
        locks.lock(LOCK);
        if (warehouses.existsByCodeKey(key(code))) {
            throw ApiProblem.conflictField("code", CODE_TAKEN);
        }
        Warehouse warehouse = new Warehouse(Ids.newId(), code, key(code), name, address);
        save(warehouse);
        audit.record(AuditEntry.of("WarehouseCreated", "Warehouse", warehouse.getId()).withAfter(snapshot(warehouse)));
        return view(warehouse);
    }

    @Transactional
    public WarehouseView update(UUID id, WarehouseCommand command, Long version) {
        Warehouse warehouse = find(id);
        if (version == null) {
            throw ApiProblem.badRequestField("version", "Reload the record and try again.");
        }
        if (warehouse.getVersion() != version) {
            throw ApiProblem.conflict(STALE);
        }
        if (warehouse.isArchived()) {
            throw ApiProblem.conflict(ARCHIVED);
        }
        String code = code(command.code());
        String name = Text.required(command.name(), 100, "name").replaceAll("\\s+", " ");
        String address = Text.optional(command.address(), 500, "address");
        locks.lock(LOCK);
        if (warehouses.existsByCodeKeyAndIdNot(key(code), id)) {
            throw ApiProblem.conflictField("code", CODE_TAKEN);
        }
        Map<String, Object> before = snapshot(warehouse);
        warehouse.apply(code, key(code), name, address);
        save(warehouse);
        audit.record(AuditEntry.of("WarehouseUpdated", "Warehouse", id).withBefore(before).withAfter(snapshot(warehouse)));
        return view(warehouse);
    }

    @Transactional
    public WarehouseView archive(UUID id) {
        TenantContext.requireTenantId();
        Warehouse warehouse = warehouses.findByIdForUpdate(id).orElseThrow(() -> ApiProblem.notFound(NOT_FOUND));
        if (!warehouse.isArchived()) {
            locks.lock(LOCK);
            if (warehouses.countByArchivedAtIsNull() <= 1) {
                throw ApiProblem.conflict(LAST_ACTIVE);
            }
            requireUnused(warehouse);
            warehouse.archive(Instant.now());
            warehouses.flush();
            audit.record(AuditEntry.of("WarehouseArchived", "Warehouse", id).withBefore(snapshot(warehouse)));
        }
        return view(warehouse);
    }

    @Transactional
    public WarehouseView restore(UUID id) {
        Warehouse warehouse = find(id);
        if (warehouse.isArchived()) {
            warehouse.restore();
            warehouses.flush();
            audit.record(AuditEntry.of("WarehouseRestored", "Warehouse", id).withAfter(snapshot(warehouse)));
        }
        return view(warehouse);
    }

    /** Idempotent: a workspace that has any warehouse keeps them. */
    @Transactional
    public void seedDefault() {
        TenantContext.requireTenantId();
        locks.lock(LOCK);
        if (warehouses.count() == 0) {
            warehouses.saveAndFlush(new Warehouse(Ids.newId(), "MAIN", "main", "Main warehouse", null));
        }
    }

    /** A warehouse referenced from a request body: unknown is 400 on {@code field}, archived is 409. */
    Warehouse requireActive(UUID id, String field) {
        Warehouse warehouse = resolve(id, field);
        requireNotArchived(warehouse);
        return warehouse;
    }

    /** Looks the warehouse up (FOR SHARE, held to commit; call inside the caller's transaction): unknown is 400. */
    Warehouse resolve(UUID id, String field) {
        TenantContext.requireTenantId();
        Warehouse warehouse = id == null ? null : warehouses.findByIdForShare(id).orElse(null);
        if (warehouse == null) {
            throw ApiProblem.badRequestField(field, UNKNOWN);
        }
        return warehouse;
    }

    static void requireNotArchived(Warehouse warehouse) {
        if (warehouse.isArchived()) {
            throw ApiProblem.conflict(ARCHIVED);
        }
    }

    Map<UUID, Warehouse> byIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return warehouses.findAllById(Set.copyOf(ids)).stream()
                .collect(Collectors.toMap(Warehouse::getId, w -> w, (a, b) -> a, LinkedHashMap::new));
    }

    static WarehouseRef ref(Warehouse w) {
        return w == null ? null : new WarehouseRef(w.getId(), w.getCode(), w.getName());
    }

    /**
     * Refuses archiving while the warehouse is in use: it holds stock, or open purchase or sales orders use it. JDBC with an explicit tenant predicate on top of RLS.
     */
    private void requireUnused(Warehouse warehouse) {
        UUID tenant = TenantContext.requireTenantId();
        Boolean stocked = jdbc.queryForObject("select exists (select 1 from stock_levels "
                + "where tenant_id = ? and warehouse_id = ? and on_hand > 0)", Boolean.class, tenant, warehouse.getId());
        if (Boolean.TRUE.equals(stocked)) {
            throw ApiProblem.conflict("Move or count out this warehouse's stock first.");
        }
        Boolean ordered = jdbc.queryForObject("select exists (select 1 from purchase_orders where tenant_id = ? "
                + "and warehouse_id = ? and status in ('DRAFT', 'ORDERED', 'PARTIALLY_RECEIVED'))", Boolean.class,
                tenant, warehouse.getId());
        if (Boolean.TRUE.equals(ordered)) {
            throw ApiProblem.conflict(OPEN_ORDERS);
        }
        Boolean selling = jdbc.queryForObject("select exists (select 1 from sales_orders where tenant_id = ? "
                + "and warehouse_id = ? and status in ('DRAFT', 'CONFIRMED'))", Boolean.class, tenant, warehouse.getId());
        if (Boolean.TRUE.equals(selling)) {
            throw ApiProblem.conflict(OPEN_ORDERS);
        }
    }

    private Warehouse find(UUID id) {
        TenantContext.requireTenantId();
        return warehouses.findById(id).orElseThrow(() -> ApiProblem.notFound(NOT_FOUND));
    }

    private void save(Warehouse warehouse) {
        try {
            warehouses.saveAndFlush(warehouse);
        } catch (DataIntegrityViolationException race) {
            throw ApiProblem.conflictField("code", CODE_TAKEN);
        }
    }

    private static String code(String raw) {
        String code = Text.required(raw, 20, "code");
        if (!CODE.matcher(code).matches()) {
            throw ApiProblem.badRequestField("code", "Use letters, digits, - and _ only, up to 20 characters.");
        }
        return code;
    }

    private static String key(String code) {
        return code.toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> snapshot(Warehouse w) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("code", w.getCode());
        values.put("name", w.getName());
        if (w.getAddress() != null) {
            values.put("address", w.getAddress());
        }
        return values;
    }

    static WarehouseView view(Warehouse w) {
        return new WarehouseView(w.getId(), w.getCode(), w.getName(), w.getAddress(), w.getArchivedAt(), w.getVersion());
    }
}
