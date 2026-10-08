package com.nexusops.inventory;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestInventory;
import com.nexusops.support.TestTenants;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** A product can't stop being GOODS while Inventory still holds it (D2): stock, a rule or an open order. */
@AutoConfigureMockMvc
class ProductKindGuardIT extends IntegrationTestSupport {

    static final String IN_USE = "This product has stock, reorder rules or open orders in Inventory.";

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    Api owner;
    UUID party, widget, main;

    @BeforeEach
    void workspace() throws Exception {
        owner = Api.login(mvc, TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("kind")));
        TestInventory.enable(owner);
        party = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Konkan Supplies\"}"));
        widget = TestInventory.goods(owner, "W-1", "Widget");
        main = TestInventory.mainWarehouse(owner);
    }

    private ResultActions kind(UUID product, String kind, long version) throws Exception {
        return owner.put("/api/v1/products/" + product, "{\"sku\":\"W-1\",\"name\":\"Widget\",\"kind\":\"" + kind
                + "\",\"version\":" + version + "}");
    }

    private void count(String quantity) throws Exception {
        owner.post("/api/v1/inventory/adjustments", "{\"productId\":\"" + widget + "\",\"warehouseId\":\"" + main
                + "\",\"countedQuantity\":" + quantity + ",\"reason\":\"Count\"}").andExpect(status().isOk());
    }

    @Test
    void stockKeepsAProductGoodsUntilItIsCountedOut() throws Exception {
        count("5");
        kind(widget, "SERVICE", 0).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(IN_USE));
        // other edits of a stocked product are fine
        owner.put("/api/v1/products/" + widget, "{\"sku\":\"W-1\",\"name\":\"Blue widget\",\"kind\":\"GOODS\","
                + "\"version\":0}").andExpect(status().isOk());
        count("0");
        kind(widget, "SERVICE", 1).andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("SERVICE"));
        // its empty level no longer lists it as stock
        owner.get("/api/v1/inventory/stock").andExpect(jsonPath("$.total").value(0));
        kind(widget, "GOODS", 2).andExpect(status().isOk());
    }

    @Test
    void reorderRulesAndOpenOrdersKeepItGoods() throws Exception {
        UUID rule = Api.id(owner.put("/api/v1/inventory/reorder-rules", "{\"productId\":\"" + widget
                + "\",\"warehouseId\":\"" + main + "\",\"minQuantity\":1,\"maxQuantity\":5}").andExpect(status().isOk()));
        kind(widget, "SERVICE", 0).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(IN_USE));
        owner.delete("/api/v1/inventory/reorder-rules/" + rule).andExpect(status().isNoContent());

        UUID purchase = Api.id(owner.post("/api/v1/purchase-orders", "{\"supplierId\":\"" + party + "\",\"warehouseId\":\""
                + main + "\",\"lines\":[{\"productId\":\"" + widget + "\",\"quantity\":1,\"unitCost\":1}]}")
                .andExpect(status().isCreated()));
        kind(widget, "SERVICE", 0).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(IN_USE));
        owner.post("/api/v1/purchase-orders/" + purchase + "/cancel", "{\"version\":0}").andExpect(status().isOk());

        UUID sale = Api.id(owner.post("/api/v1/sales-orders", "{\"customerId\":\"" + party + "\",\"warehouseId\":\""
                + main + "\",\"lines\":[{\"productId\":\"" + widget + "\",\"quantity\":1,\"unitPrice\":1}]}")
                .andExpect(status().isCreated()));
        kind(widget, "SERVICE", 0).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(IN_USE));
        owner.post("/api/v1/sales-orders/" + sale + "/cancel", "{\"version\":0}").andExpect(status().isOk());

        // closed orders don't hold it
        kind(widget, "SERVICE", 0).andExpect(status().isOk());
    }

    @Test
    void aProductInventoryNeverSawChangesKindFreely() throws Exception {
        kind(widget, "SERVICE", 0).andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("SERVICE"));
        owner.get("/api/v1/products/" + widget).andExpect(jsonPath("$.version").value(Matchers.greaterThan(0)));
    }
}
