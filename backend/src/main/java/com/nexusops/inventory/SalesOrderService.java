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
import com.nexusops.inventory.domain.SalesOrder;
import com.nexusops.inventory.domain.SalesOrderLine;
import com.nexusops.inventory.domain.SalesOrderLineRepository;
import com.nexusops.inventory.domain.SalesOrderRepository;
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

/** Sales orders (D10): DRAFT → CONFIRMED (reserves) → FULFILLED (issues); DRAFT/CONFIRMED → CANCELLED (releases). */
@Service
public class SalesOrderService {

    static final String DRAFT_ONLY = "Only a draft can be changed.";
    static final String CONFIRM_DRAFT_ONLY = "Only a draft can be confirmed.";
    static final String FULFIL_CONFIRMED_ONLY = "Only a confirmed sales order can be fulfilled.";
    static final String NOT_CANCELLABLE = "Only a draft or confirmed sales order can be cancelled.";

    private final SalesOrderRepository orders;
    private final SalesOrderLineRepository lines;
    private final NumberSequences numbers;
    private final InventoryProducts products;
    private final WarehouseService warehouses;
    private final OrderParties orderParties;
    private final PartyService parties;
    private final StockLedger ledger;
    private final Members members;
    private final TenantDirectory tenants;
    private final AuditService audit;

    SalesOrderService(SalesOrderRepository orders, SalesOrderLineRepository lines, NumberSequences numbers,
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

    private record Draft(UUID customerId, UUID warehouseId, String currency, String notes,
            List<SalesLineCommand> lines) {}

    @Transactional(readOnly = true)
    public SalesOrderView get(UUID id) {
        SalesOrder order = find(id);
        return view(order, lines.findByOrderIdOrderByLineNoAsc(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<SalesOrderSummary> list(SalesOrderQuery query, Integer page, Integer size) {
        TenantContext.requireTenantId();
        Specification<SalesOrder> spec = (root, cq, cb) -> cb.conjunction();
        if (query.status() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("status"), query.status()));
        }
        if (query.customerId() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("customerId"), query.customerId()));
        }
        if (query.warehouseId() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("warehouseId"), query.warehouseId()));
        }
        String q = Text.optional(query.q(), 100, "q");
        if (q != null) {
            String like = Text.containsPattern(q);
            spec = spec.and(orderParties.numberOrPartyName(like, "customerId"));
        }
        Page<SalesOrder> result = orders.findAll(spec,
                Paging.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        List<SalesOrder> found = result.getContent();
        Map<UUID, List<SalesOrderLine>> byOrder = lines.findByOrderIdInOrderByLineNoAsc(
                found.stream().map(SalesOrder::getId).toList()).stream()
                .collect(Collectors.groupingBy(SalesOrderLine::getOrderId));
        Map<UUID, PartyRef> customers = orderParties.refs(found.stream().map(SalesOrder::getCustomerId)
                .collect(Collectors.toSet()));
        Map<UUID, Warehouse> places = warehouses.byIds(found.stream().map(SalesOrder::getWarehouseId)
                .collect(Collectors.toSet()));
        return new PageResponse<>(found.stream().map(o -> {
            List<SalesOrderLine> ls = byOrder.getOrDefault(o.getId(), List.of());
            return new SalesOrderSummary(o.getId(), o.getNumber(), customers.get(o.getCustomerId()),
                    WarehouseService.ref(places.get(o.getWarehouseId())), o.getStatus(), o.getCurrency(), total(ls),
                    ls.size(), o.getCreatedAt());
        }).toList(), result.getNumber(), result.getSize(), result.getTotalElements());
    }

    @Transactional
    public SalesOrderView create(SalesOrderCommand command) {
        TenantContext.requireTenantId();
        Draft draft = validate(command, null, List.of());
        SalesOrder order = new SalesOrder(Ids.newId(), numbers.next(SequenceKind.SALES_ORDER), draft.customerId(),
                draft.warehouseId(), draft.currency(), draft.notes(), TenantContext.userId().orElse(null));
        orders.saveAndFlush(order);
        List<SalesOrderLine> saved = saveLines(order.getId(), draft.lines());
        audit.record(AuditEntry.of("SalesOrderCreated", "SalesOrder", order.getId()).withAfter(snapshot(order, saved)));
        return view(order, saved);
    }

    @Transactional
    public SalesOrderView update(UUID id, SalesOrderCommand command, Long version) {
        SalesOrder order = find(id);
        Orders.checkVersion(order.getVersion(), version);
        if (order.getStatus() != SalesOrderStatus.DRAFT) {
            throw ApiProblem.conflict(DRAFT_ONLY);
        }
        List<SalesOrderLine> current = lines.findByOrderIdOrderByLineNoAsc(id);
        Draft draft = validate(command, order, current);
        Map<String, Object> before = snapshot(order, current);
        order.apply(draft.customerId(), draft.warehouseId(), draft.currency(), draft.notes());
        orders.flush();
        lines.deleteByOrderId(id);
        lines.flush();
        List<SalesOrderLine> saved = saveLines(id, draft.lines());
        audit.record(AuditEntry.of("SalesOrderUpdated", "SalesOrder", id).withBefore(before)
                .withAfter(snapshot(order, saved)));
        return view(order, saved);
    }

    /** All or nothing: lock every line's level, then either reserve them all or report every shortage. */
    @Transactional
    public SalesOrderView confirm(UUID id, Long version) {
        SalesOrder order = find(id);
        Orders.checkVersion(order.getVersion(), version);
        if (order.getStatus() != SalesOrderStatus.DRAFT) {
            throw ApiProblem.conflict(CONFIRM_DRAFT_ONLY);
        }
        orderParties.requireNotArchived(order.getCustomerId());
        warehouses.requireActive(order.getWarehouseId(), "warehouseId");
        List<SalesOrderLine> ls = lines.findByOrderIdOrderByLineNoAsc(id);
        // Take the order's optimistic lock before any stock is touched; a shortage rolls the transition back.
        order.confirm(Instant.now());
        orders.flush();
        Map<StockKey, StockLevel> locked = lock(order, ls);
        List<Shortage> shortages = new ArrayList<>();
        Map<UUID, ProductBrief> names = products.briefs(ls.stream().map(SalesOrderLine::getProductId).toList());
        for (SalesOrderLine line : ls) {
            StockLevel level = locked.get(key(order, line));
            if (level.getAvailable().compareTo(line.getQuantity()) < 0) {
                shortages.add(new Shortage(line.getProductId(), names.get(line.getProductId()).sku(),
                        line.getQuantity(), level.getAvailable()));
            }
        }
        if (!shortages.isEmpty()) {
            throw Shortage.conflict(shortages);
        }
        ls.forEach(line -> ledger.reserve(locked.get(key(order, line)), line.getQuantity()));
        parties.ensureRole(order.getCustomerId(), PartyRoleType.CUSTOMER);
        audit.record(AuditEntry.of("SalesOrderConfirmed", "SalesOrder", id).withAfter(Map.of("number", order.getNumber())));
        return view(order, ls);
    }

    @Transactional
    public SalesOrderView fulfil(UUID id, Long version) {
        SalesOrder order = find(id);
        Orders.checkVersion(order.getVersion(), version);
        if (order.getStatus() != SalesOrderStatus.CONFIRMED) {
            throw ApiProblem.conflict(FULFIL_CONFIRMED_ONLY);
        }
        List<SalesOrderLine> ls = lines.findByOrderIdOrderByLineNoAsc(id);
        order.fulfil(Instant.now());
        orders.flush();
        Map<StockKey, StockLevel> locked = lock(order, ls);
        for (SalesOrderLine line : ls) {
            StockLevel level = locked.get(key(order, line));
            ledger.release(level, line.getQuantity());
            ledger.move(level, MovementKind.ISSUE, line.getQuantity().negate(), ReferenceType.SALES_ORDER,
                    order.getId(), order.getNumber());
        }
        audit.record(AuditEntry.of("SalesOrderFulfilled", "SalesOrder", id).withAfter(Map.of("number", order.getNumber())));
        return view(order, ls);
    }

    @Transactional
    public SalesOrderView cancel(UUID id, Long version) {
        SalesOrder order = find(id);
        Orders.checkVersion(order.getVersion(), version);
        if (!order.getStatus().cancellable()) {
            throw ApiProblem.conflict(NOT_CANCELLABLE);
        }
        SalesOrderStatus before = order.getStatus();
        List<SalesOrderLine> ls = lines.findByOrderIdOrderByLineNoAsc(id);
        order.cancel(Instant.now());
        orders.flush();
        if (before == SalesOrderStatus.CONFIRMED) {
            Map<StockKey, StockLevel> locked = lock(order, ls);
            ls.forEach(line -> ledger.release(locked.get(key(order, line)), line.getQuantity()));
        }
        audit.record(AuditEntry.of("SalesOrderCancelled", "SalesOrder", id)
                .withBefore(Map.of("status", before.name())).withAfter(Map.of("number", order.getNumber())));
        return view(order, ls);
    }

    private Map<StockKey, StockLevel> lock(SalesOrder order, List<SalesOrderLine> ls) {
        return ledger.lock(ls.stream().map(line -> key(order, line)).toList());
    }

    private static StockKey key(SalesOrder order, SalesOrderLine line) {
        return new StockKey(line.getProductId(), order.getWarehouseId());
    }

    /** {@code current} is null on create. An unchanged customer, warehouse or line product isn't re-checked. */
    private Draft validate(SalesOrderCommand command, SalesOrder current, List<SalesOrderLine> currentLines) {
        Orders.requireCount(command.lines());
        Set<UUID> existingProducts = currentLines.stream().map(SalesOrderLine::getProductId)
                .collect(Collectors.toSet());
        Set<UUID> seen = new HashSet<>();
        List<SalesLineCommand> checked = new ArrayList<>();
        for (int i = 0; i < command.lines().size(); i++) {
            SalesLineCommand l = command.lines().get(i);
            if (l == null) {
                throw ApiProblem.badRequestField(Orders.item(i), Orders.EMPTY_LINE);
            }
            BigDecimal quantity = Quantities.positive(l.quantity(), Orders.field(i, "quantity"));
            BigDecimal price = Decimals.nonNegative(l.unitPrice(), Orders.field(i, "unitPrice"),
                    "Enter a price of 0 or more.");
            if (l.productId() != null && !seen.add(l.productId())) {
                throw ApiProblem.badRequestField(Orders.field(i, "productId"), "This product is already on the order.");
            }
            checked.add(new SalesLineCommand(l.productId(), quantity, price));
        }
        String currency = Currencies.parse(command.currency(), "currency");
        String orderCurrency = currency != null ? currency : tenants.currentSettings().currency();
        String notes = Text.optional(command.notes(), 2000, "notes");
        if (command.customerId() == null) {
            throw ApiProblem.badRequestField("customerId", "Choose a customer.");
        }
        boolean newCustomer = current == null || !command.customerId().equals(current.getCustomerId());
        boolean newWarehouse = current == null || !Objects.equals(command.warehouseId(), current.getWarehouseId());
        // 400s first: resolve every product, default the prices, then every changed reference; 403/409 follow.
        List<ProductBrief> resolved = new ArrayList<>();
        List<SalesLineCommand> clean = new ArrayList<>();
        for (int i = 0; i < checked.size(); i++) {
            SalesLineCommand l = checked.get(i);
            ProductBrief product = products.resolve(l.productId(), Orders.field(i, "productId"));
            resolved.add(product);
            BigDecimal price = l.unitPrice();
            if (price == null) {
                if (product.listPrice() == null || !orderCurrency.equals(product.currency())) {
                    throw ApiProblem.badRequestField(Orders.field(i, "unitPrice"), "Enter a price.");
                }
                price = product.listPrice();
            }
            clean.add(new SalesLineCommand(l.productId(), l.quantity(), price));
        }
        Warehouse warehouse = newWarehouse ? warehouses.resolve(command.warehouseId(), "warehouseId") : null;
        // The customer goes last: resolving it can answer 403, which must follow every 400.
        PartyBrief customer = newCustomer
                ? orderParties.resolve(command.customerId(), "customerId", "Choose a customer.") : null;
        for (ProductBrief product : resolved) {
            if (!existingProducts.contains(product.id())) {
                InventoryProducts.requireNotArchived(product);
            }
        }
        if (customer != null) {
            OrderParties.requireNotArchived(customer);
        }
        if (warehouse != null) {
            WarehouseService.requireNotArchived(warehouse);
        }
        return new Draft(command.customerId(), command.warehouseId(), orderCurrency, notes, clean);
    }

    private List<SalesOrderLine> saveLines(UUID orderId, List<SalesLineCommand> commands) {
        List<SalesOrderLine> rows = new ArrayList<>();
        for (int i = 0; i < commands.size(); i++) {
            SalesLineCommand c = commands.get(i);
            rows.add(new SalesOrderLine(Ids.newId(), orderId, i + 1, c.productId(), c.quantity(), c.unitPrice()));
        }
        return lines.saveAllAndFlush(rows);
    }

    private SalesOrderView view(SalesOrder o, List<SalesOrderLine> ls) {
        Map<UUID, ProductBrief> names = products.briefs(ls.stream().map(SalesOrderLine::getProductId)
                .collect(Collectors.toSet()));
        Members.Member creator = o.getCreatedBy() == null ? null
                : members.findAll(List.of(o.getCreatedBy())).get(o.getCreatedBy());
        List<SalesLineView> lineViews = ls.stream().map(l -> new SalesLineView(l.getId(), l.getLineNo(),
                InventoryProducts.ref(names.get(l.getProductId())), l.getQuantity(), l.getUnitPrice(),
                Orders.lineTotal(l.getQuantity(), l.getUnitPrice()))).toList();
        return new SalesOrderView(o.getId(), o.getNumber(),
                orderParties.refs(List.of(o.getCustomerId())).get(o.getCustomerId()),
                WarehouseService.ref(warehouses.byIds(List.of(o.getWarehouseId())).get(o.getWarehouseId())),
                o.getStatus(), o.getCurrency(), o.getNotes(), lineViews, total(ls), o.getConfirmedAt(),
                o.getFulfilledAt(), o.getCancelledAt(),
                creator == null ? null : new MemberRef(creator.id(), creator.name()), o.getCreatedAt(),
                o.getUpdatedAt(), o.getVersion());
    }

    private static BigDecimal total(List<SalesOrderLine> ls) {
        return ls.stream().map(l -> Orders.lineTotal(l.getQuantity(), l.getUnitPrice()))
                .reduce(BigDecimal.ZERO.setScale(4), BigDecimal::add);
    }

    private static Map<String, Object> snapshot(SalesOrder o, List<SalesOrderLine> ls) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("number", o.getNumber());
        values.put("customerId", o.getCustomerId().toString());
        values.put("warehouseId", o.getWarehouseId().toString());
        values.put("currency", o.getCurrency());
        values.put("lines", ls.stream().map(l -> Map.of("productId", l.getProductId().toString(),
                "quantity", l.getQuantity().toPlainString(), "unitPrice", l.getUnitPrice().toPlainString())).toList());
        return values;
    }

    private SalesOrder find(UUID id) {
        TenantContext.requireTenantId();
        return orders.findById(id).orElseThrow(() -> ApiProblem.notFound(Orders.NOT_FOUND));
    }
}
