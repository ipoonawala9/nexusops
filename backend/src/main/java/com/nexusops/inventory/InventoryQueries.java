package com.nexusops.inventory;

import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** SQL read models for the stock list, reorder suggestions and the overview (D11–D13). */
@Component
class InventoryQueries {

    /** Product × warehouse pairs with a level or a rule, with on-hand, reserved, on order, rule and 30-day usage. */
    private static final String PAIRS = """
            with pairs as (
                select product_id, warehouse_id from stock_levels where tenant_id = :tenant
                union
                select product_id, warehouse_id from reorder_rules where tenant_id = :tenant
            ),
            on_order as (
                select l.product_id, o.warehouse_id, sum(l.quantity - l.received_quantity) as quantity
                from purchase_order_lines l
                join purchase_orders o on o.tenant_id = l.tenant_id and o.id = l.order_id
                where l.tenant_id = :tenant and o.tenant_id = :tenant
                  and o.status in ('ORDERED', 'PARTIALLY_RECEIVED')
                group by l.product_id, o.warehouse_id
            ),
            used as (
                select product_id, warehouse_id, -sum(quantity) as quantity
                from stock_movements
                where tenant_id = :tenant and kind = 'ISSUE' and occurred_at >= now() - interval '30 days'
                group by product_id, warehouse_id
            ),
            rows as (
                select p.id as product_id, p.sku, p.name as product_name, p.unit,
                       w.id as warehouse_id, w.code as warehouse_code, w.name as warehouse_name,
                       coalesce(s.on_hand, 0) as on_hand, coalesce(s.reserved, 0) as reserved,
                       coalesce(s.on_hand, 0) - coalesce(s.reserved, 0) as available,
                       coalesce(oo.quantity, 0) as on_order, coalesce(u.quantity, 0) as used,
                       r.id as rule_id, r.min_quantity, r.max_quantity, r.supplier_id
                from pairs x
                join products p on p.tenant_id = :tenant and p.id = x.product_id and p.archived_at is null
                     and p.kind = 'GOODS'
                join warehouses w on w.tenant_id = :tenant and w.id = x.warehouse_id and w.archived_at is null
                left join stock_levels s on s.tenant_id = :tenant and s.product_id = x.product_id
                     and s.warehouse_id = x.warehouse_id
                left join reorder_rules r on r.tenant_id = :tenant and r.product_id = x.product_id
                     and r.warehouse_id = x.warehouse_id
                left join on_order oo on oo.product_id = x.product_id and oo.warehouse_id = x.warehouse_id
                left join used u on u.product_id = x.product_id and u.warehouse_id = x.warehouse_id
            )
            """;

    /** A total order, so pages never repeat or skip a row. */
    private static final String ORDER = "lower(product_name), lower(sku), warehouse_code, product_id, warehouse_id";

    private static final String BELOW_MIN = "rule_id is not null and available + on_order < min_quantity";

    record Row(UUID productId, String sku, String productName, String unit, UUID warehouseId, String warehouseCode,
            String warehouseName, BigDecimal onHand, BigDecimal reserved, BigDecimal available, BigDecimal onOrder,
            BigDecimal used, UUID ruleId, BigDecimal minQuantity, BigDecimal maxQuantity, UUID supplierId) {

        ProductRef product() {
            return new ProductRef(productId, sku, productName, unit);
        }

        WarehouseRef warehouse() {
            return new WarehouseRef(warehouseId, warehouseCode, warehouseName);
        }

        boolean belowMin() {
            return ruleId != null
                    && new ReorderMath.Figures(available, onOrder, minQuantity, maxQuantity, used).belowMin();
        }
    }

    record Counts(long belowMinimum, long purchaseOrdersAwaitingReceipt, long salesOrdersAwaitingFulfilment) {}

    private final NamedParameterJdbcTemplate jdbc;

    InventoryQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Row> stockRows(StockQuery query, int limit, long offset) {
        MapSqlParameterSource params = params().addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(PAIRS + "select * from rows where " + filters(query, params)
                + " order by " + ORDER + " limit :limit offset :offset", params,
                InventoryQueries::row);
    }

    long countStockRows(StockQuery query) {
        MapSqlParameterSource params = params();
        Long total = jdbc.queryForObject(PAIRS + "select count(*) from rows where " + filters(query, params), params,
                Long.class);
        return total == null ? 0 : total;
    }

    /** Every rule below its minimum, for active products in active warehouses. */
    List<Row> belowMinimum() {
        return jdbc.query(PAIRS + "select * from rows where " + BELOW_MIN
                + " order by " + ORDER, params(), InventoryQueries::row);
    }

    Counts counts() {
        MapSqlParameterSource params = params();
        Long below = jdbc.queryForObject(PAIRS + "select count(*) from rows where " + BELOW_MIN, params, Long.class);
        Long receiving = jdbc.queryForObject("select count(*) from purchase_orders where tenant_id = :tenant "
                + "and status in ('ORDERED', 'PARTIALLY_RECEIVED')", params, Long.class);
        Long shipping = jdbc.queryForObject("select count(*) from sales_orders where tenant_id = :tenant "
                + "and status = 'CONFIRMED'", params, Long.class);
        return new Counts(below == null ? 0 : below, receiving == null ? 0 : receiving, shipping == null ? 0 : shipping);
    }

    /** The unit cost on each product's most recently received purchase line in {@code currency}. */
    Map<UUID, BigDecimal> lastReceivedCosts(Collection<UUID> productIds, String currency) {
        Map<UUID, BigDecimal> costs = new HashMap<>();
        if (productIds.isEmpty()) {
            return costs;
        }
        jdbc.query("""
                select distinct on (l.product_id) l.product_id, l.unit_cost
                from purchase_order_lines l
                join purchase_orders o on o.tenant_id = l.tenant_id and o.id = l.order_id
                where l.tenant_id = :tenant and o.tenant_id = :tenant and l.product_id in (:products)
                  and l.received_quantity > 0 and o.currency = :currency
                order by l.product_id, o.updated_at desc, l.id
                """, params().addValue("products", productIds).addValue("currency", currency),
                rs -> {
                    costs.put(rs.getObject("product_id", UUID.class), rs.getBigDecimal("unit_cost"));
                });
        return costs;
    }

    private static String filters(StockQuery query, MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder("true");
        if (query.warehouseId() != null) {
            where.append(" and warehouse_id = :warehouse");
            params.addValue("warehouse", query.warehouseId());
        }
        String q = Text.optional(query.q(), 100, "q");
        if (q != null) {
            where.append(" and (lower(sku) like :pattern escape '\\' or lower(product_name) like :pattern escape '\\')");
            params.addValue("pattern", Text.containsPattern(q));
        }
        if (query.belowMin()) {
            where.append(" and ").append(BELOW_MIN);
        }
        return where.toString();
    }

    private static MapSqlParameterSource params() {
        return new MapSqlParameterSource("tenant", TenantContext.requireTenantId());
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject("product_id", UUID.class), rs.getString("sku"), rs.getString("product_name"),
                rs.getString("unit"), rs.getObject("warehouse_id", UUID.class), rs.getString("warehouse_code"),
                rs.getString("warehouse_name"), rs.getBigDecimal("on_hand"), rs.getBigDecimal("reserved"),
                rs.getBigDecimal("available"), rs.getBigDecimal("on_order"), rs.getBigDecimal("used"),
                rs.getObject("rule_id", UUID.class), rs.getBigDecimal("min_quantity"),
                rs.getBigDecimal("max_quantity"), rs.getObject("supplier_id", UUID.class));
    }
}
