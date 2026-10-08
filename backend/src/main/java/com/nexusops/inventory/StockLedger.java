package com.nexusops.inventory;

import com.nexusops.inventory.domain.StockLevel;
import com.nexusops.inventory.domain.StockLevelRepository;
import com.nexusops.inventory.domain.StockMovement;
import com.nexusops.inventory.domain.StockMovementRepository;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only writer of stock (D5, D6). Callers lock the levels they need (one call, all keys), check availability, then
 * move, reserve or release. Every on-hand change appends one ledger row in the same transaction; the table CHECKs
 * reject negative stock and over-reservation if a caller ever gets it wrong.
 */
@Component
class StockLedger {

    private static final String CREATE_LEVEL = """
            insert into stock_levels (id, tenant_id, product_id, warehouse_id, on_hand, reserved, updated_at, version)
            values (?, ?, ?, ?, 0, 0, now(), 0)
            on conflict (tenant_id, product_id, warehouse_id) do nothing
            """;

    private final StockLevelRepository levels;
    private final StockMovementRepository movements;
    private final JdbcTemplate jdbc;

    StockLedger(StockLevelRepository levels, StockMovementRepository movements, JdbcTemplate jdbc) {
        this.levels = levels;
        this.movements = movements;
        this.jdbc = jdbc;
    }

    /**
     * Creates missing level rows, then locks every row in key order. Must run inside the caller's transaction.
     * Rules for callers: call this once per transaction with every key the operation needs (locking again later could
     * invert the lock order), and never load a StockLevel before locking it in the same transaction (a locking query
     * does not refresh an already-managed entity, so the values would be stale).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Map<StockKey, StockLevel> lock(Collection<StockKey> keys) {
        UUID tenant = TenantContext.requireTenantId();
        List<StockKey> sorted = keys.stream().distinct().sorted().toList();
        for (StockKey key : sorted) {
            jdbc.update(CREATE_LEVEL, Ids.newId(), tenant, key.productId(), key.warehouseId());
        }
        Map<StockKey, StockLevel> locked = new LinkedHashMap<>();
        for (StockKey key : sorted) {
            locked.put(key, levels.lock(key.productId(), key.warehouseId())
                    .orElseThrow(() -> new IllegalStateException("Stock level vanished: " + key)));
        }
        return locked;
    }

    /** Changes on-hand by {@code delta} (signed) and appends the ledger row. The level must be locked. */
    @Transactional(propagation = Propagation.MANDATORY)
    StockMovement move(StockLevel level, MovementKind kind, BigDecimal delta, ReferenceType referenceType,
            UUID referenceId, String reason) {
        level.changeOnHand(delta);
        levels.flush();
        StockMovement movement = new StockMovement(Ids.newId(), level.getProductId(), level.getWarehouseId(), kind,
                delta, level.getOnHand(), referenceType, referenceId, reason, TenantContext.userId().orElse(null),
                Instant.now());
        movements.saveAndFlush(movement);
        return movement;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void reserve(StockLevel level, BigDecimal quantity) {
        level.reserve(quantity);
        levels.flush();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void release(StockLevel level, BigDecimal quantity) {
        level.release(quantity);
        levels.flush();
    }
}
