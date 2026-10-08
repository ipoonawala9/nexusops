package com.nexusops.inventory;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.catalog.ProductBrief;
import com.nexusops.catalog.ProductKind;
import com.nexusops.collaboration.MemberRef;
import com.nexusops.identity.Members;
import com.nexusops.inventory.domain.StockLevel;
import com.nexusops.inventory.domain.StockLevelRepository;
import com.nexusops.inventory.domain.StockMovement;
import com.nexusops.inventory.domain.StockMovementRepository;
import com.nexusops.inventory.domain.Warehouse;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.shared.web.PageResponse;
import com.nexusops.shared.web.Paging;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stock reads, counts (D7) and transfers (D8). Orders move stock through StockLedger in their own services. */
@Service
public class StockService {

    static final String NOT_FOUND = "Record not found.";
    static final String BELOW_RESERVED = "Reserved stock can't be counted away. Release the reservations first.";
    static final String SAME_WAREHOUSE = "Choose a different warehouse.";

    private final StockLedger ledger;
    private final StockLevelRepository levels;
    private final StockMovementRepository movements;
    private final InventoryProducts products;
    private final WarehouseService warehouses;
    private final Members members;
    private final AuditService audit;
    private final InventoryQueries queries;

    StockService(StockLedger ledger, StockLevelRepository levels, StockMovementRepository movements,
            InventoryProducts products, WarehouseService warehouses, Members members, AuditService audit,
            InventoryQueries queries) {
        this.ledger = ledger;
        this.levels = levels;
        this.movements = movements;
        this.products = products;
        this.warehouses = warehouses;
        this.members = members;
        this.audit = audit;
        this.queries = queries;
    }

    @Transactional(readOnly = true)
    public ProductStock productStock(UUID productId) {
        TenantContext.requireTenantId();
        ProductBrief product = products.briefs(List.of(productId)).get(productId);
        if (product == null) {
            throw ApiProblem.notFound(NOT_FOUND);
        }
        if (product.kind() != ProductKind.GOODS) {
            throw ApiProblem.badRequest(InventoryProducts.SERVICE);
        }
        Map<UUID, StockLevel> byWarehouse = levels.findByProductId(productId).stream()
                .collect(Collectors.toMap(StockLevel::getWarehouseId, Function.identity()));
        List<ProductStock.Level> rows = new ArrayList<>();
        BigDecimal onHand = BigDecimal.ZERO;
        BigDecimal reserved = BigDecimal.ZERO;
        for (WarehouseView w : warehouses.list(false)) {
            StockLevel level = byWarehouse.get(w.id());
            BigDecimal h = level == null ? BigDecimal.ZERO : level.getOnHand();
            BigDecimal r = level == null ? BigDecimal.ZERO : level.getReserved();
            rows.add(new ProductStock.Level(new WarehouseRef(w.id(), w.code(), w.name()), h, r, h.subtract(r)));
            onHand = onHand.add(h);
            reserved = reserved.add(r);
        }
        return new ProductStock(InventoryProducts.ref(product), rows, onHand, reserved, onHand.subtract(reserved));
    }

    @Transactional(readOnly = true)
    public PageResponse<MovementView> movements(MovementQuery query, Integer page, Integer size) {
        TenantContext.requireTenantId();
        Specification<StockMovement> spec = (root, cq, cb) -> cb.conjunction();
        if (query.productId() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("productId"), query.productId()));
        }
        if (query.warehouseId() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("warehouseId"), query.warehouseId()));
        }
        if (query.kind() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("kind"), query.kind()));
        }
        Page<StockMovement> result = movements.findAll(spec,
                Paging.of(page, size, Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"))));
        return new PageResponse<>(views(result.getContent()), result.getNumber(), result.getSize(),
                result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public PageResponse<StockRow> list(StockQuery query, Integer page, Integer size) {
        TenantContext.requireTenantId();
        Pageable paging = Paging.of(page, size);
        List<StockRow> rows = queries.stockRows(query, paging.getPageSize(), paging.getOffset()).stream()
                .map(r -> new StockRow(r.product(), r.warehouse(), r.onHand(), r.reserved(), r.available(),
                        r.onOrder(), r.ruleId(), r.minQuantity(), r.maxQuantity(), r.belowMin()))
                .toList();
        return new PageResponse<>(rows, paging.getPageNumber(), paging.getPageSize(), queries.countStockRows(query));
    }

    @Transactional(readOnly = true)
    public InventoryOverview overview() {
        TenantContext.requireTenantId();
        InventoryQueries.Counts counts = queries.counts();
        return new InventoryOverview(counts.belowMinimum(), counts.purchaseOrdersAwaitingReceipt(),
                counts.salesOrdersAwaitingFulfilment(), movements(new MovementQuery(null, null, null), 0, 10).items());
    }

    @Transactional
    public ProductStock adjust(AdjustCommand command) {
        TenantContext.requireTenantId();
        BigDecimal counted = Quantities.count(command.countedQuantity(), "countedQuantity");
        String reason = Text.required(command.reason(), 200, "reason");
        ProductBrief product = products.resolve(command.productId(), "productId");
        Warehouse warehouse = warehouses.resolve(command.warehouseId(), "warehouseId");
        InventoryProducts.requireNotArchived(product);
        WarehouseService.requireNotArchived(warehouse);
        StockKey key = new StockKey(product.id(), warehouse.getId());
        StockLevel level = ledger.lock(List.of(key)).get(key);
        if (counted.compareTo(level.getReserved()) < 0) {
            throw ApiProblem.conflict(BELOW_RESERVED);
        }
        BigDecimal before = level.getOnHand();
        BigDecimal delta = counted.subtract(before);
        if (delta.signum() != 0) {
            ledger.move(level, MovementKind.ADJUSTMENT, delta, ReferenceType.ADJUSTMENT, Ids.newId(), reason);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("warehouseId", warehouse.getId().toString());
            after.put("onHand", level.getOnHand().toPlainString());
            after.put("reason", reason);
            audit.record(AuditEntry.of("StockAdjusted", "Product", product.id())
                    .withBefore(Map.of("warehouseId", warehouse.getId().toString(), "onHand", before.toPlainString()))
                    .withAfter(after));
        }
        return productStock(product.id());
    }

    @Transactional
    public ProductStock transfer(TransferCommand command) {
        TenantContext.requireTenantId();
        BigDecimal quantity = Quantities.positive(command.quantity(), "quantity");
        String note = Text.optional(command.note(), 200, "note");
        if (command.fromWarehouseId() != null && command.fromWarehouseId().equals(command.toWarehouseId())) {
            throw ApiProblem.badRequestField("toWarehouseId", SAME_WAREHOUSE);
        }
        ProductBrief product = products.resolve(command.productId(), "productId");
        Warehouse from = warehouses.resolve(command.fromWarehouseId(), "fromWarehouseId");
        Warehouse to = warehouses.resolve(command.toWarehouseId(), "toWarehouseId");
        InventoryProducts.requireNotArchived(product);
        WarehouseService.requireNotArchived(from);
        WarehouseService.requireNotArchived(to);
        StockKey fromKey = new StockKey(product.id(), from.getId());
        StockKey toKey = new StockKey(product.id(), to.getId());
        Map<StockKey, StockLevel> locked = ledger.lock(List.of(fromKey, toKey));
        StockLevel source = locked.get(fromKey);
        if (source.getAvailable().compareTo(quantity) < 0) {
            throw Shortage.conflict(List.of(new Shortage(product.id(), product.sku(), quantity, source.getAvailable())));
        }
        UUID transferId = Ids.newId();
        ledger.move(source, MovementKind.TRANSFER_OUT, quantity.negate(), ReferenceType.TRANSFER, transferId, note);
        ledger.move(locked.get(toKey), MovementKind.TRANSFER_IN, quantity, ReferenceType.TRANSFER, transferId, note);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("fromWarehouseId", from.getId().toString());
        after.put("toWarehouseId", to.getId().toString());
        after.put("quantity", quantity.toPlainString());
        if (note != null) {
            after.put("note", note);
        }
        audit.record(AuditEntry.of("StockTransferred", "Product", product.id()).withAfter(after));
        return productStock(product.id());
    }

    List<MovementView> views(List<StockMovement> page) {
        Map<UUID, ProductBrief> productNames = products.briefs(page.stream().map(StockMovement::getProductId)
                .collect(Collectors.toSet()));
        Map<UUID, Warehouse> warehouseNames = warehouses.byIds(page.stream().map(StockMovement::getWarehouseId)
                .collect(Collectors.toSet()));
        Map<UUID, Members.Member> people = members.findAll(page.stream().map(StockMovement::getActorId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return page.stream().map(m -> {
            Members.Member actor = m.getActorId() == null ? null : people.get(m.getActorId());
            return new MovementView(m.getId(), InventoryProducts.ref(productNames.get(m.getProductId())),
                    WarehouseService.ref(warehouseNames.get(m.getWarehouseId())), m.getKind(), m.getQuantity(),
                    m.getOnHandAfter(), m.getReferenceType(), m.getReferenceId(), m.getReason(),
                    actor == null ? null : new MemberRef(actor.id(), actor.name()), m.getOccurredAt());
        }).toList();
    }
}
