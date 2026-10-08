package com.nexusops.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

/** Enables the Inventory module and creates stockable products for tests. */
public final class TestInventory {

    private TestInventory() {}

    public static void enable(Api owner) throws Exception {
        owner.put("/api/v1/tenant/modules/INVENTORY", "{\"enabled\":true}").andExpect(status().isOk());
    }

    public static UUID goods(Api owner, String sku, String name) throws Exception {
        return Api.id(owner.post("/api/v1/products", "{\"sku\":\"" + sku + "\",\"name\":\"" + name + "\",\"listPrice\":10}")
                .andExpect(status().isCreated()));
    }

    public static UUID mainWarehouse(Api owner) throws Exception {
        java.util.List<String> ids = Api.read(owner.get("/api/v1/inventory/warehouses"), "$[?(@.code == 'MAIN')].id");
        return UUID.fromString(ids.getFirst());
    }

    /** Every stock level's on-hand equals the sum of its ledger rows (ADR-0011): the projection agrees with the books. */
    public static void assertLedgerBalances(UUID tenantId) {
        assertThat(OwnerJdbc.ownerAs(tenantId).queryForObject("""
                select count(*) from stock_levels l
                where l.on_hand <> coalesce((select sum(m.quantity) from stock_movements m
                                             where m.product_id = l.product_id and m.warehouse_id = l.warehouse_id), 0)
                """, Long.class)).as("levels that disagree with the ledger").isZero();
    }
}
