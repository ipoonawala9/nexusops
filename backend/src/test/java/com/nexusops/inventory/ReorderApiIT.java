package com.nexusops.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestInventory;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class ReorderApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID supplier, customer, widget, gadget, main;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("reorder"));
        owner = Api.login(mvc, ws);
        TestInventory.enable(owner);
        supplier = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Konkan Supplies\"}"));
        customer = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Deccan Retail\"}"));
        widget = TestInventory.goods(owner, "W-1", "Widget");
        gadget = TestInventory.goods(owner, "G-1", "Gadget");
        main = TestInventory.mainWarehouse(owner);
    }

    private ResultActions rule(UUID product, String min, String max, UUID preferred, Long version) throws Exception {
        return owner.put("/api/v1/inventory/reorder-rules", "{\"productId\":\"" + product + "\",\"warehouseId\":\""
                + main + "\",\"minQuantity\":" + min + ",\"maxQuantity\":" + max
                + (preferred == null ? "" : ",\"supplierId\":\"" + preferred + "\"")
                + (version == null ? "" : ",\"version\":" + version) + "}");
    }

    private void count(UUID product, String quantity) throws Exception {
        owner.post("/api/v1/inventory/adjustments", "{\"productId\":\"" + product + "\",\"warehouseId\":\"" + main
                + "\",\"countedQuantity\":" + quantity + ",\"reason\":\"Count\"}").andExpect(status().isOk());
    }

    /** Sells and ships {@code quantity} so it counts as usage. */
    private void sell(UUID product, String quantity) throws Exception {
        UUID order = Api.id(owner.post("/api/v1/sales-orders", "{\"customerId\":\"" + customer + "\",\"warehouseId\":\""
                + main + "\",\"lines\":[{\"productId\":\"" + product + "\",\"quantity\":" + quantity
                + ",\"unitPrice\":1}]}").andExpect(status().isCreated()));
        owner.post("/api/v1/sales-orders/" + order + "/confirm", "{\"version\":0}").andExpect(status().isOk());
        owner.post("/api/v1/sales-orders/" + order + "/fulfil", "{\"version\":1}").andExpect(status().isOk());
    }

    private long audits(String action) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = ?", Long.class, action);
    }

    @Test
    void rulesAreUpsertedPerProductAndWarehouse() throws Exception {
        UUID id = Api.id(rule(widget, "20", "60", supplier, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.product.sku").value("W-1"))
                .andExpect(jsonPath("$.supplier.name").value("Konkan Supplies"))
                .andExpect(jsonPath("$.version").value(0)));
        rule(widget, "10", "30", null, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("version"));
        rule(widget, "10", "30", null, 5L).andExpect(status().isConflict());
        rule(widget, "10", "30", null, 0L).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.minQuantity").value(10.0)).andExpect(jsonPath("$.supplier").doesNotExist());
        owner.get("/api/v1/inventory/reorder-rules?productId=" + widget).andExpect(jsonPath("$.length()").value(1));
        owner.get("/api/v1/inventory/reorder-rules?productId=" + gadget).andExpect(jsonPath("$.length()").value(0));
        owner.delete("/api/v1/inventory/reorder-rules/" + id).andExpect(status().isNoContent());
        owner.delete("/api/v1/inventory/reorder-rules/" + id).andExpect(status().isNotFound());
        assertThat(audits("ReorderRuleSaved")).isEqualTo(2);
        assertThat(audits("ReorderRuleDeleted")).isEqualTo(1);
    }

    @Test
    void invalidRulesAreFieldErrors() throws Exception {
        UUID service = Api.id(owner.post("/api/v1/products", "{\"sku\":\"S-1\",\"name\":\"Setup\",\"kind\":\"SERVICE\"}"));
        rule(widget, "-1", "5", null, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("minQuantity"));
        rule(widget, "5", "5", null, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("maxQuantity"));
        rule(service, "1", "5", null, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("productId"));
        rule(widget, "1", "5", UUID.randomUUID(), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("supplierId"));
    }

    @Test
    void theStockListShowsLevelsRulesAndWhatIsOnOrder() throws Exception {
        count(widget, "12");
        rule(gadget, "5", "10", supplier, null).andExpect(status().isOk());
        UUID po = Api.id(owner.post("/api/v1/purchase-orders", "{\"supplierId\":\"" + supplier + "\",\"warehouseId\":\""
                + main + "\",\"lines\":[{\"productId\":\"" + gadget + "\",\"quantity\":2,\"unitCost\":3}]}"));
        owner.post("/api/v1/purchase-orders/" + po + "/order", "{\"version\":0}").andExpect(status().isOk());
        owner.get("/api/v1/inventory/stock").andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[*].product.sku").value(Matchers.contains("G-1", "W-1")))
                .andExpect(jsonPath("$.items[0].onHand").value(0.0))
                .andExpect(jsonPath("$.items[0].onOrder").value(2.0))
                .andExpect(jsonPath("$.items[0].minQuantity").value(5.0))
                .andExpect(jsonPath("$.items[0].belowMin").value(true))
                .andExpect(jsonPath("$.items[1].available").value(12.0))
                .andExpect(jsonPath("$.items[1].ruleId").doesNotExist())
                .andExpect(jsonPath("$.items[1].belowMin").value(false));
        owner.get("/api/v1/inventory/stock?belowMin=true").andExpect(jsonPath("$.total").value(1));
        owner.get("/api/v1/inventory/stock?q=widg").andExpect(jsonPath("$.items[*].product.sku")
                .value(Matchers.contains("W-1")));
        UUID pune = Api.id(owner.post("/api/v1/inventory/warehouses", "{\"code\":\"PUNE\",\"name\":\"Pune\"}"));
        owner.get("/api/v1/inventory/stock?warehouseId=" + pune).andExpect(jsonPath("$.total").value(0));
        owner.post("/api/v1/products/" + widget + "/archive", "").andExpect(status().isOk());
        owner.get("/api/v1/inventory/stock").andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void suggestionsExplainThemselvesAndBecomeDraftOrders() throws Exception {
        count(widget, "50");
        sell(widget, "38");
        rule(widget, "20", "60", supplier, null).andExpect(status().isOk());
        count(gadget, "3");
        rule(gadget, "5", "10", null, null).andExpect(status().isOk());
        owner.get("/api/v1/inventory/reorder-suggestions").andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].product.sku").value("G-1"))
                .andExpect(jsonPath("$[0].daysOfCover").doesNotExist())
                .andExpect(jsonPath("$[0].explanation").value("3 available, 0 on order, below the minimum of 5; "
                        + "no usage in the last 30 days; order 7 to reach 10"))
                .andExpect(jsonPath("$[1].available").value(12.0))
                .andExpect(jsonPath("$[1].usedLast30Days").value(38.0))
                .andExpect(jsonPath("$[1].suggestedQuantity").value(48.0))
                .andExpect(jsonPath("$[1].supplier.name").value("Konkan Supplies"))
                .andExpect(jsonPath("$[1].explanation").value("12 available, 0 on order, below the minimum of 20; "
                        + "38 used in the last 30 days (about 9 days of cover); order 48 to reach 60"));

        String items = "{\"items\":[{\"productId\":\"" + widget + "\",\"warehouseId\":\"" + main + "\",\"quantity\":48}";
        owner.post("/api/v1/inventory/reorder-suggestions/purchase-orders", items + ",{\"productId\":\"" + gadget
                + "\",\"warehouseId\":\"" + main + "\",\"quantity\":7}]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("items[1].productId"));
        owner.post("/api/v1/inventory/reorder-suggestions/purchase-orders", items + "]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.orders[0].supplier.name").value("Konkan Supplies"))
                .andExpect(jsonPath("$.orders[0].lines[0].quantity").value(48.0))
                .andExpect(jsonPath("$.orders[0].lines[0].unitCost").value(0.0));
        // a draft isn't on order yet; ordering it takes the product off the suggestions
        owner.get("/api/v1/inventory/reorder-suggestions").andExpect(jsonPath("$.length()").value(2));
        UUID draft = UUID.fromString(Api.read(owner.get("/api/v1/purchase-orders?status=DRAFT"), "$.items[0].id"));
        owner.post("/api/v1/purchase-orders/" + draft + "/order", "{\"version\":0}").andExpect(status().isOk());
        owner.get("/api/v1/inventory/reorder-suggestions").andExpect(jsonPath("$[*].product.sku")
                .value(Matchers.contains("G-1")));
    }

    @Test
    void draftsUseTheLastReceivedCostAndGroupBySupplier() throws Exception {
        UUID other = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Malabar Traders\"}"));
        UUID po = Api.id(owner.post("/api/v1/purchase-orders", "{\"supplierId\":\"" + supplier + "\",\"warehouseId\":\""
                + main + "\",\"lines\":[{\"productId\":\"" + widget + "\",\"quantity\":1,\"unitCost\":4.25}]}"));
        owner.post("/api/v1/purchase-orders/" + po + "/order", "{\"version\":0}").andExpect(status().isOk());
        String line = Api.<List<String>>read(owner.get("/api/v1/purchase-orders/" + po), "$.lines[*].id").get(0);
        owner.post("/api/v1/purchase-orders/" + po + "/receipts", "{\"lines\":[{\"lineId\":\"" + line
                + "\",\"quantity\":1}],\"version\":1}").andExpect(status().isOk());
        rule(widget, "20", "60", supplier, null).andExpect(status().isOk());
        rule(gadget, "5", "10", other, null).andExpect(status().isOk());
        owner.post("/api/v1/inventory/reorder-suggestions/purchase-orders", "{\"items\":[{\"productId\":\"" + widget
                + "\",\"warehouseId\":\"" + main + "\",\"quantity\":59},{\"productId\":\"" + gadget
                + "\",\"warehouseId\":\"" + main + "\",\"quantity\":10}]}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.orders.length()").value(2))
                .andExpect(jsonPath("$.orders[0].supplier.name").value("Konkan Supplies"))
                .andExpect(jsonPath("$.orders[0].lines[0].unitCost").value(4.25))
                .andExpect(jsonPath("$.orders[1].supplier.name").value("Malabar Traders"));
    }

    @Test
    void theOverviewCountsWhatNeedsAttention() throws Exception {
        count(widget, "10");
        rule(gadget, "5", "10", supplier, null).andExpect(status().isOk());
        UUID po = Api.id(owner.post("/api/v1/purchase-orders", "{\"supplierId\":\"" + supplier + "\",\"warehouseId\":\""
                + main + "\",\"lines\":[{\"productId\":\"" + gadget + "\",\"quantity\":2,\"unitCost\":3}]}"));
        owner.post("/api/v1/purchase-orders/" + po + "/order", "{\"version\":0}").andExpect(status().isOk());
        UUID so = Api.id(owner.post("/api/v1/sales-orders", "{\"customerId\":\"" + customer + "\",\"warehouseId\":\""
                + main + "\",\"lines\":[{\"productId\":\"" + widget + "\",\"quantity\":1,\"unitPrice\":1}]}"));
        owner.post("/api/v1/sales-orders/" + so + "/confirm", "{\"version\":0}").andExpect(status().isOk());
        owner.get("/api/v1/inventory/overview")
                .andExpect(jsonPath("$.belowMinimum").value(1))
                .andExpect(jsonPath("$.purchaseOrdersAwaitingReceipt").value(1))
                .andExpect(jsonPath("$.salesOrdersAwaitingFulfilment").value(1))
                .andExpect(jsonPath("$.recentMovements[0].product.sku").value("W-1"));
    }

    @Test
    void permissions() throws Exception {
        UUID readerRole = TestRoles.create(mvc, owner.session(), "Stock reader", "inventory.stock.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(readerRole)));
        reader.get("/api/v1/inventory/reorder-suggestions").andExpect(status().isOk());
        reader.get("/api/v1/inventory/stock").andExpect(status().isOk());
        reader.get("/api/v1/inventory/overview").andExpect(status().isOk());
        reader.put("/api/v1/inventory/reorder-rules", "{}").andExpect(status().isForbidden());
        UUID plannerRole = TestRoles.create(mvc, owner.session(), "Planner", "inventory.stock.read",
                "inventory.reorder.manage");
        Api planner = Api.login(mvc, members.create(ws.tenantId(), Set.of(plannerRole)));
        planner.post("/api/v1/inventory/reorder-suggestions/purchase-orders", "{\"items\":[]}")
                .andExpect(status().isForbidden());
    }
}
