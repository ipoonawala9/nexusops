package com.nexusops.support;

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
}
