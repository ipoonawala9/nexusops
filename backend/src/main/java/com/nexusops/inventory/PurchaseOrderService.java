package com.nexusops.inventory;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.catalog.ProductBrief;
import com.nexusops.collaboration.MemberRef;
import com.nexusops.directory.PartyBrief;
import com.nexusops.directory.PartyRef;
import com.nexusops.directory.PartyRoleType;
import com.nexusops.directory.PartyService;
import com.nexusops.identity.Members;
import com.nexusops.inventory.domain.PurchaseOrder;
import com.nexusops.inventory.domain.PurchaseOrderLine;
import com.nexusops.inventory.domain.PurchaseOrderLineRepository;
import com.nexusops.inventory.domain.PurchaseOrderRepository;
import com.nexusops.inventory.domain.StockLevel;
import com.nexusops.inventory.domain.Warehouse;
import com.nexusops.shared.Currencies;
import com.nexusops.shared.Decimals;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.shared.web.PageResponse;
import com.nexusops.shared.web.Paging;
import com.nexusops.tenancy.TenantDirectory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Purchase orders (D9): DRAFT → ORDERED → PARTIALLY_RECEIVED → RECEIVED; DRAFT/ORDERED → CANCELLED. */
@Service
public class PurchaseOrderService {

    static final String DRAFT_ONLY = "Only a draft can be changed.";
    static final String ORDER_DRAFT_ONLY = "Only a draft can be ordered.";
    static final String NOT_RECEIVABLE = "Only an ordered purchase order can be received.";
    static final String NOT_CANCELLABLE = "Only a draft or ordered purchase order can be cancelled.";

    private final PurchaseOrderRepository orders;
    private final PurchaseOrderLineRepository lines;
    private final NumberSequences numbers;
    private final InventoryProducts products;
    private final WarehouseService warehouses;
    private final OrderParties orderParties;
    private final PartyService parties;
    private final StockLedger ledger;
    private final Members members;
    private final TenantDirectory tenants;
    private final AuditService audit;

    PurchaseOrderService(PurchaseOrderRepository orders, PurchaseOrderLineRepository lines, NumberSequences numbers,
            InventoryProducts products, WarehouseService warehouses, OrderParties orderParties, PartyService parties,
            StockLedger ledger, Members members, TenantDirectory tenants, AuditService audit) {
        this.orders = orders;
        this.lines = lines;
        this.numbers = numbers;
        this.products = products;
        this.warehouses = warehouses;
        this.orderParties = orderParties;
        this.parties = parties;
        this.ledger = ledger;
        this.members = members;
        this.tenants = tenants;
        this.audit = audit;
    }

    private record Draft(UUID supplierId, UUID warehouseId, String currency, LocalDate expectedOn, String notes,
            List<PurchaseLineCommand> lines) {}

    @Transactional(readOnly = true)
    public PurchaseOrderView get(UUID id) {
        PurchaseOrder order = find(id);
        return view(order, lines.findByOrderIdOrderByLineNoAsc(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<PurchaseOrderSummary> list(PurchaseOrderQuery query, Integer page, Integer size) {
        TenantContext.requireTenantId();
        Specification<PurchaseOrder> spec = (root, cq, cb) -> cb.conjunction();
        if (query.status() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("status"), query.status()));
        }
        if (query.supplierId() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("supplierId"), query.supplierId()));
        }
        if (query.warehouseId() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("warehouseId"), query.warehouseId()));
        }
        String q = Text.optional(query.q(), 100, "q");
        if (q != null) {
            String like = Text.containsPattern(q);
            spec = spec.and((root, cq, cb) -> cb.like(cb.lower(root.get("number")), like, '\\'));
        }
        Page<PurchaseOrder> result = orders.findAll(spec,
                Paging.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        List<PurchaseOrder> found = result.getContent();
        Map<UUID, List<PurchaseOrderLine>> byOrder = lines.findByOrderIdInOrderByLineNoAsc(
                found.stream().map(PurchaseOrder::getId).toList()).stream()
                .collect(Collectors.groupingBy(PurchaseOrderLine::getOrderId));
        Map<UUID, PartyRef> suppliers = orderParties.refs(found.stream().map(PurchaseOrder::getSupplierId)
                .collect(Collectors.toSet()));
        Map<UUID, Warehouse> places = warehouses.byIds(found.stream().map(PurchaseOrder::getWarehouseId)
                .collect(Collectors.toSet()));
        return new PageResponse<>(found.stream().map(o -> {
            List<PurchaseOrderLine> ls = byOrder.getOrDefault(o.getId(), List.of());
            return new PurchaseOrderSummary(o.getId(), o.getNumber(), suppliers.get(o.getSupplierId()),
                    WarehouseService.ref(places.get(o.getWarehouseId())), o.getStatus(), o.getCurrency(), total(ls),
                    ls.size(), o.getExpectedOn(), o.getCreatedAt());
        }).toList(), result.getNumber(), result.getSize(), result.getTotalElements());
    }

    @Transactional
    public PurchaseOrderView create(PurchaseOrderCommand command) {
        TenantContext.requireTenantId();
        Draft draft = validate(command, null, List.of());
        PurchaseOrder order = new PurchaseOrder(Ids.newId(), numbers.next(SequenceKind.PURCHASE_ORDER),
                draft.supplierId(), draft.warehouseId(), draft.currency(), draft.expectedOn(), draft.notes(),
                TenantContext.userId().orElse(null));
        orders.saveAndFlush(order);
        List<PurchaseOrderLine> saved = saveLines(order.getId(), draft.lines());
        audit.record(AuditEntry.of("PurchaseOrderCreated", "PurchaseOrder", order.getId())
                .withAfter(snapshot(order, saved)));
        return view(order, saved);
    }

    @Transactional
    public PurchaseOrderView update(UUID id, PurchaseOrderCommand command, Long version) {
        PurchaseOrder order = find(id);
        Orders.checkVersion(order.getVersion(), version);
        if (order.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw ApiProblem.conflict(DRAFT_ONLY);
        }
        List<PurchaseOrderLine> current = lines.findByOrderIdOrderByLineNoAsc(id);
        Draft draft = validate(command, order, current);
        Map<String, Object> before = snapshot(order, current);
        order.apply(draft.supplierId(), draft.warehouseId(), draft.currency(), draft.expectedOn(), draft.notes());
        orders.flush();
        lines.deleteByOrderId(id);
        lines.flush();
        List<PurchaseOrderLine> saved = saveLines(id, draft.lines());
        audit.record(AuditEntry.of("PurchaseOrderUpdated", "PurchaseOrder", id).withBefore(before)
                .withAfter(snapshot(order, saved)));
        return view(order, saved);
    }

    @Transactional
    public PurchaseOrderView order(UUID id, Long version) {
        PurchaseOrder order = find(id);
        Orders.checkVersion(order.getVersion(), version);
        if (order.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw ApiProblem.conflict(ORDER_DRAFT_ONLY);
        }
        orderParties.requireNotArchived(order.getSupplierId());
        order.order(Instant.now());
        orders.flush();
        parties.ensureRole(order.getSupplierId(), PartyRoleType.SUPPLIER);
        audit.record(AuditEntry.of("PurchaseOrderOrdered", "PurchaseOrder", id)
                .withAfter(Map.of("number", order.getNumber())));
        return view(order, lines.findByOrderIdOrderByLineNoAsc(id));
    }

    @Transactional
    public PurchaseOrderView cancel(UUID id, Long version) {
        PurchaseOrder order = find(id);
        Orders.checkVersion(order.getVersion(), version);
        if (!order.getStatus().cancellable()) {
            throw ApiProblem.conflict(NOT_CANCELLABLE);
        }
        PurchaseOrderStatus before = order.getStatus();
        order.cancel(Instant.now());
        orders.flush();
        audit.record(AuditEntry.of("PurchaseOrderCancelled", "PurchaseOrder", id)
                .withBefore(Map.of("status", before.name())).withAfter(Map.of("number", order.getNumber())));
        return view(order, lines.findByOrderIdOrderByLineNoAsc(id));
    }

    @Transactional
    public PurchaseOrderView receive(UUID id, ReceiptCommand command, Long version) {
        PurchaseOrder order = find(id);
        Orders.checkVersion(order.getVersion(), version);
        List<ReceiptCommand.Line> receipt = command.lines();
        if (receipt == null || receipt.isEmpty()) {
            throw ApiProblem.badRequestField("lines", "Choose what was received.");
        }
        if (!order.getStatus().receivable()) {
            throw ApiProblem.conflict(NOT_RECEIVABLE);
        }
        Map<UUID, PurchaseOrderLine> byId = lines.findByOrderIdOrderByLineNoAsc(id).stream()
                .collect(Collectors.toMap(PurchaseOrderLine::getId, l -> l, (a, b) -> a, LinkedHashMap::new));
        Set<UUID> seen = new HashSet<>();
        Map<PurchaseOrderLine, BigDecimal> amounts = new LinkedHashMap<>();
        for (int i = 0; i < receipt.size(); i++) {
            ReceiptCommand.Line r = receipt.get(i);
            PurchaseOrderLine line = r.lineId() == null ? null : byId.get(r.lineId());
            if (line == null) {
                throw ApiProblem.badRequestField(Orders.field(i, "lineId"), "Choose a line of this order.");
            }
            if (!seen.add(line.getId())) {
                throw ApiProblem.badRequestField(Orders.field(i, "lineId"), "This line is already in the receipt.");
            }
            BigDecimal quantity = Quantities.positive(r.quantity(), Orders.field(i, "quantity"));
            if (quantity.compareTo(line.getRemaining()) > 0) {
                throw ApiProblem.badRequestField(Orders.field(i, "quantity"),
                        "Receive at most " + line.getRemaining().stripTrailingZeros().toPlainString() + ".");
            }
            amounts.put(line, quantity);
        }
        // Take the order's optimistic lock first (one version bump): a concurrent receipt of the same order fails
        // here with 409, before it can touch the lines or the stock.
        boolean complete = byId.values().stream()
                .allMatch(l -> l.getRemaining().subtract(amounts.getOrDefault(l, BigDecimal.ZERO)).signum() == 0);
        order.received(complete, Instant.now());
        orders.flush();
        Map<StockKey, StockLevel> locked = ledger.lock(amounts.keySet().stream()
                .map(l -> new StockKey(l.getProductId(), order.getWarehouseId())).toList());
        List<Map<String, Object>> received = new ArrayList<>();
        amounts.forEach((line, quantity) -> {
            ledger.move(locked.get(new StockKey(line.getProductId(), order.getWarehouseId())), MovementKind.RECEIPT,
                    quantity, ReferenceType.PURCHASE_ORDER, order.getId(), order.getNumber());
            line.receive(quantity);
            received.add(Map.of("productId", line.getProductId().toString(), "quantity", quantity.toPlainString()));
        });
        lines.flush();
        audit.record(AuditEntry.of("PurchaseOrderReceived", "PurchaseOrder", id)
                .withAfter(Map.of("number", order.getNumber(), "lines", received, "status", order.getStatus().name())));
        return view(order, new ArrayList<>(byId.values()));
    }

    /** {@code current} is null on create. An unchanged supplier, warehouse or line product isn't re-checked. */
    private Draft validate(PurchaseOrderCommand command, PurchaseOrder current, List<PurchaseOrderLine> currentLines) {
        Orders.requireCount(command.lines());
        Set<UUID> existingProducts = currentLines.stream().map(PurchaseOrderLine::getProductId)
                .collect(Collectors.toSet());
        Set<UUID> seen = new HashSet<>();
        List<PurchaseLineCommand> clean = new ArrayList<>();
        for (int i = 0; i < command.lines().size(); i++) {
            PurchaseLineCommand l = command.lines().get(i);
            BigDecimal quantity = Quantities.positive(l.quantity(), Orders.field(i, "quantity"));
            if (l.unitCost() == null) {
                throw ApiProblem.badRequestField(Orders.field(i, "unitCost"), "Enter a cost.");
            }
            BigDecimal cost = Decimals.nonNegative(l.unitCost(), Orders.field(i, "unitCost"),
                    "Enter a cost of 0 or more.");
            if (l.productId() != null && !seen.add(l.productId())) {
                throw ApiProblem.badRequestField(Orders.field(i, "productId"), "This product is already on the order.");
            }
            clean.add(new PurchaseLineCommand(l.productId(), quantity, cost));
        }
        String currency = Currencies.parse(command.currency(), "currency");
        String notes = Text.optional(command.notes(), 2000, "notes");
        if (command.supplierId() == null) {
            throw ApiProblem.badRequestField("supplierId", "Choose a supplier.");
        }
        boolean newSupplier = current == null || !command.supplierId().equals(current.getSupplierId());
        boolean newWarehouse = current == null || !Objects.equals(command.warehouseId(), current.getWarehouseId());
        // 400s first: resolve every changed reference, then apply the 409 archived checks.
        List<ProductBrief> resolved = new ArrayList<>();
        for (int i = 0; i < clean.size(); i++) {
            resolved.add(products.resolve(clean.get(i).productId(), Orders.field(i, "productId")));
        }
        Warehouse warehouse = newWarehouse ? warehouses.resolve(command.warehouseId(), "warehouseId") : null;
        // The supplier goes last: resolving it can answer 403, which must follow every 400.
        PartyBrief supplier = newSupplier
                ? orderParties.resolve(command.supplierId(), "supplierId", "Choose a supplier.") : null;
        for (ProductBrief product : resolved) {
            if (!existingProducts.contains(product.id())) {
                InventoryProducts.requireNotArchived(product);
            }
        }
        if (supplier != null) {
            OrderParties.requireNotArchived(supplier);
        }
        if (warehouse != null) {
            WarehouseService.requireNotArchived(warehouse);
        }
        return new Draft(command.supplierId(), command.warehouseId(),
                currency != null ? currency : tenants.currentSettings().currency(), command.expectedOn(), notes, clean);
    }

    private List<PurchaseOrderLine> saveLines(UUID orderId, List<PurchaseLineCommand> commands) {
        List<PurchaseOrderLine> rows = new ArrayList<>();
        for (int i = 0; i < commands.size(); i++) {
            PurchaseLineCommand c = commands.get(i);
            rows.add(new PurchaseOrderLine(Ids.newId(), orderId, i + 1, c.productId(), c.quantity(), c.unitCost()));
        }
        return lines.saveAllAndFlush(rows);
    }

    private PurchaseOrderView view(PurchaseOrder o, List<PurchaseOrderLine> ls) {
        Map<UUID, ProductBrief> names = products.briefs(ls.stream().map(PurchaseOrderLine::getProductId)
                .collect(Collectors.toSet()));
        Members.Member creator = o.getCreatedBy() == null ? null
                : members.findAll(List.of(o.getCreatedBy())).get(o.getCreatedBy());
        List<PurchaseLineView> lineViews = ls.stream().map(l -> new PurchaseLineView(l.getId(), l.getLineNo(),
                InventoryProducts.ref(names.get(l.getProductId())), l.getQuantity(), l.getReceivedQuantity(),
                l.getRemaining(), l.getUnitCost(), Orders.lineTotal(l.getQuantity(), l.getUnitCost()))).toList();
        return new PurchaseOrderView(o.getId(), o.getNumber(),
                orderParties.refs(List.of(o.getSupplierId())).get(o.getSupplierId()),
                WarehouseService.ref(warehouses.byIds(List.of(o.getWarehouseId())).get(o.getWarehouseId())),
                o.getStatus(), o.getCurrency(), o.getExpectedOn(), o.getNotes(), lineViews, total(ls),
                o.getOrderedAt(), o.getReceivedAt(), o.getCancelledAt(),
                creator == null ? null : new MemberRef(creator.id(), creator.name()), o.getCreatedAt(),
                o.getUpdatedAt(), o.getVersion());
    }

    private static BigDecimal total(List<PurchaseOrderLine> ls) {
        return ls.stream().map(l -> Orders.lineTotal(l.getQuantity(), l.getUnitCost()))
                .reduce(BigDecimal.ZERO.setScale(4), BigDecimal::add);
    }

    private static Map<String, Object> snapshot(PurchaseOrder o, List<PurchaseOrderLine> ls) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("number", o.getNumber());
        values.put("supplierId", o.getSupplierId().toString());
        values.put("warehouseId", o.getWarehouseId().toString());
        values.put("currency", o.getCurrency());
        values.put("lines", ls.stream().map(l -> Map.of("productId", l.getProductId().toString(),
                "quantity", l.getQuantity().toPlainString(), "unitCost", l.getUnitCost().toPlainString())).toList());
        return values;
    }

    private PurchaseOrder find(UUID id) {
        TenantContext.requireTenantId();
        return orders.findById(id).orElseThrow(() -> ApiProblem.notFound(Orders.NOT_FOUND));
    }
}
