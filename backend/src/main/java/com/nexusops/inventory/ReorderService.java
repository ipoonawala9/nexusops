package com.nexusops.inventory;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.catalog.ProductBrief;
import com.nexusops.directory.PartyBrief;
import com.nexusops.directory.PartyRef;
import com.nexusops.inventory.domain.ReorderRule;
import com.nexusops.inventory.domain.ReorderRuleRepository;
import com.nexusops.inventory.domain.Warehouse;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.tenancy.TenantDirectory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reorder rules and explained suggestions (D11–D12). */
@Service
public class ReorderService {

    static final String MAX_ABOVE_MIN = "Enter a maximum above the minimum.";
    static final String NO_RULE = "This product has no reorder rule for that warehouse.";
    static final String NO_SUPPLIER = "Set a preferred supplier on this product's reorder rule first.";
    static final String DUPLICATE = "This product and warehouse are already in the list.";

    private final ReorderRuleRepository rules;
    private final InventoryProducts products;
    private final WarehouseService warehouses;
    private final OrderParties parties;
    private final InventoryQueries queries;
    private final PurchaseOrderService purchaseOrders;
    private final TenantDirectory tenants;
    private final TenantLocks locks;
    private final AuditService audit;

    ReorderService(ReorderRuleRepository rules, InventoryProducts products, WarehouseService warehouses,
            OrderParties parties, InventoryQueries queries, PurchaseOrderService purchaseOrders,
            TenantDirectory tenants, TenantLocks locks, AuditService audit) {
        this.rules = rules;
        this.products = products;
        this.warehouses = warehouses;
        this.parties = parties;
        this.queries = queries;
        this.purchaseOrders = purchaseOrders;
        this.tenants = tenants;
        this.locks = locks;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<ReorderRuleView> rules(UUID productId, UUID warehouseId) {
        TenantContext.requireTenantId();
        Specification<ReorderRule> spec = (root, cq, cb) -> cb.conjunction();
        if (productId != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("productId"), productId));
        }
        if (warehouseId != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("warehouseId"), warehouseId));
        }
        return views(rules.findAll(spec, Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"))));
    }

    /** Upsert by (product, warehouse). A new rule takes no version; changing one needs the current version. */
    @Transactional
    public ReorderRuleView saveRule(ReorderRuleCommand command, Long version) {
        TenantContext.requireTenantId();
        BigDecimal min = Quantities.count(command.minQuantity(), "minQuantity");
        BigDecimal max = Quantities.count(command.maxQuantity(), "maxQuantity");
        if (max.compareTo(min) <= 0) {
            throw ApiProblem.badRequestField("maxQuantity", MAX_ABOVE_MIN);
        }
        if (command.productId() == null) {
            throw ApiProblem.badRequestField("productId", "Choose a product in this workspace.");
        }
        if (command.warehouseId() == null) {
            throw ApiProblem.badRequestField("warehouseId", "Choose a warehouse in this workspace.");
        }
        locks.lock("reorder-rule:" + command.productId() + ":" + command.warehouseId());
        ReorderRule rule = rules.findByProductIdAndWarehouseId(command.productId(), command.warehouseId())
                .orElse(null);
        if (rule != null) {
            Orders.checkVersion(rule.getVersion(), version);
        }
        // 400s first (product, warehouse, then the supplier last: it can answer 403), then the 409 archived checks.
        ProductBrief product = products.resolve(command.productId(), "productId");
        Warehouse warehouse = rule == null ? warehouses.resolve(command.warehouseId(), "warehouseId") : null;
        boolean newSupplier = command.supplierId() != null
                && (rule == null || !command.supplierId().equals(rule.getSupplierId()));
        PartyBrief supplier = newSupplier ? parties.resolve(command.supplierId(), "supplierId", "Choose a supplier.")
                : null;
        if (rule == null) {
            InventoryProducts.requireNotArchived(product);
            WarehouseService.requireNotArchived(warehouse);
        }
        if (supplier != null) {
            OrderParties.requireNotArchived(supplier);
        }
        Map<String, Object> before = rule == null ? null : snapshot(rule);
        if (rule == null) {
            rule = new ReorderRule(Ids.newId(), command.productId(), command.warehouseId());
        }
        rule.apply(min, max, command.supplierId());
        rules.saveAndFlush(rule);
        AuditEntry entry = AuditEntry.of("ReorderRuleSaved", "ReorderRule", rule.getId()).withAfter(snapshot(rule));
        audit.record(before == null ? entry : entry.withBefore(before));
        return views(List.of(rule)).get(0);
    }

    @Transactional
    public void deleteRule(UUID id) {
        TenantContext.requireTenantId();
        ReorderRule rule = rules.findById(id).orElseThrow(() -> ApiProblem.notFound(Orders.NOT_FOUND));
        rules.delete(rule);
        rules.flush();
        audit.record(AuditEntry.of("ReorderRuleDeleted", "ReorderRule", id).withBefore(snapshot(rule)));
    }

    @Transactional(readOnly = true)
    public List<ReorderSuggestion> suggestions() {
        TenantContext.requireTenantId();
        List<InventoryQueries.Row> rows = queries.belowMinimum();
        Map<UUID, PartyRef> suppliers = parties.refs(rows.stream().map(InventoryQueries.Row::supplierId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return rows.stream().map(r -> {
            ReorderMath.Figures f = new ReorderMath.Figures(r.available(), r.onOrder(), r.minQuantity(),
                    r.maxQuantity(), r.used());
            return new ReorderSuggestion(r.ruleId(), r.product(), r.warehouse(), r.available(), r.onOrder(),
                    r.minQuantity(), r.maxQuantity(), r.supplierId() == null ? null : suppliers.get(r.supplierId()),
                    r.used(), f.averageDailyUsage(), f.daysOfCover(), f.suggestedQuantity(), f.explanation());
        }).toList();
    }

    /** One DRAFT purchase order per (supplier, warehouse), in the order the items first name them. */
    @Transactional
    public DraftOrdersResult draftOrders(DraftOrdersCommand command) {
        TenantContext.requireTenantId();
        List<DraftOrdersCommand.Item> items = command.items();
        if (items == null || items.isEmpty()) {
            throw ApiProblem.badRequestField("items", "Choose at least one suggestion.");
        }
        record Key(UUID supplierId, UUID warehouseId) {}
        Map<Key, List<PurchaseLineCommand>> groups = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            DraftOrdersCommand.Item item = items.get(i);
            String field = "items[" + i + "].";
            BigDecimal quantity = Quantities.positive(item.quantity(), field + "quantity");
            if (item.productId() == null || item.warehouseId() == null) {
                throw ApiProblem.badRequestField(field + "productId", NO_RULE);
            }
            if (!seen.add(item.productId() + ":" + item.warehouseId())) {
                throw ApiProblem.badRequestField(field + "productId", DUPLICATE);
            }
            ReorderRule rule = rules.findByProductIdAndWarehouseId(item.productId(), item.warehouseId())
                    .orElseThrow(() -> ApiProblem.badRequestField(field + "productId", NO_RULE));
            if (rule.getSupplierId() == null) {
                throw ApiProblem.badRequestField(field + "productId", NO_SUPPLIER);
            }
            groups.computeIfAbsent(new Key(rule.getSupplierId(), rule.getWarehouseId()), k -> new ArrayList<>())
                    .add(new PurchaseLineCommand(item.productId(), quantity, null));
        }
        String currency = tenants.currentSettings().currency();
        Map<UUID, BigDecimal> costs = queries.lastReceivedCosts(items.stream()
                .map(DraftOrdersCommand.Item::productId).collect(Collectors.toSet()), currency);
        List<PurchaseOrderView> created = new ArrayList<>();
        groups.forEach((key, lines) -> created.add(purchaseOrders.create(new PurchaseOrderCommand(key.supplierId(),
                key.warehouseId(), currency, null, null, lines.stream().map(l -> new PurchaseLineCommand(
                        l.productId(), l.quantity(), costs.getOrDefault(l.productId(), BigDecimal.ZERO))).toList()))));
        return new DraftOrdersResult(created);
    }

    private List<ReorderRuleView> views(List<ReorderRule> list) {
        Map<UUID, ProductBrief> names = products.briefs(list.stream().map(ReorderRule::getProductId)
                .collect(Collectors.toSet()));
        Map<UUID, Warehouse> places = warehouses.byIds(list.stream().map(ReorderRule::getWarehouseId)
                .collect(Collectors.toSet()));
        Map<UUID, PartyRef> suppliers = parties.refs(list.stream().map(ReorderRule::getSupplierId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return list.stream().map(r -> new ReorderRuleView(r.getId(), InventoryProducts.ref(names.get(r.getProductId())),
                WarehouseService.ref(places.get(r.getWarehouseId())), r.getMinQuantity(), r.getMaxQuantity(),
                r.getSupplierId() == null ? null : suppliers.get(r.getSupplierId()), r.getUpdatedAt(), r.getVersion()))
                .toList();
    }

    private static Map<String, Object> snapshot(ReorderRule r) {
        Map<String, Object> values = new HashMap<>();
        values.put("productId", r.getProductId().toString());
        values.put("warehouseId", r.getWarehouseId().toString());
        values.put("minQuantity", r.getMinQuantity().toPlainString());
        values.put("maxQuantity", r.getMaxQuantity().toPlainString());
        values.put("supplierId", r.getSupplierId() == null ? null : r.getSupplierId().toString());
        return values;
    }
}
