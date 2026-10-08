package com.nexusops.inventory;

import com.nexusops.catalog.ProductKind;
import com.nexusops.catalog.ProductKindGuard;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.web.ApiProblem;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Only GOODS carry stock (D2), so a product Inventory still holds can't stop being GOODS and strand it. */
@Component
class InventoryKindGuard implements ProductKindGuard {

    static final String IN_USE = "This product has stock, reorder rules or open orders in Inventory.";

    private static final String HELD = """
            select exists (select 1 from stock_levels
                           where tenant_id = ? and product_id = ? and (on_hand > 0 or reserved > 0))
                or exists (select 1 from reorder_rules where tenant_id = ? and product_id = ?)
                or exists (select 1 from purchase_order_lines l
                           join purchase_orders o on o.tenant_id = l.tenant_id and o.id = l.order_id
                           where l.tenant_id = ? and o.tenant_id = ? and l.product_id = ?
                             and o.status in ('DRAFT', 'ORDERED', 'PARTIALLY_RECEIVED'))
                or exists (select 1 from sales_order_lines l
                           join sales_orders o on o.tenant_id = l.tenant_id and o.id = l.order_id
                           where l.tenant_id = ? and o.tenant_id = ? and l.product_id = ?
                             and o.status in ('DRAFT', 'CONFIRMED'))
            """;

    private final JdbcTemplate jdbc;

    InventoryKindGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void beforeKindChange(UUID productId, ProductKind from, ProductKind to) {
        if (from != ProductKind.GOODS || to == ProductKind.GOODS) {
            return;
        }
        UUID tenant = TenantContext.requireTenantId();
        Boolean held = jdbc.queryForObject(HELD, Boolean.class, tenant, productId, tenant, productId, tenant, tenant,
                productId, tenant, tenant, productId);
        if (Boolean.TRUE.equals(held)) {
            throw ApiProblem.conflict(IN_USE);
        }
    }
}
