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
class StockApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID widget;
    UUID main;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("stock"));
        owner = Api.login(mvc, ws);
        TestInventory.enable(owner);
        widget = TestInventory.goods(owner, "W-1", "Widget");
        main = TestInventory.mainWarehouse(owner);
    }

    private ResultActions count(UUID warehouse, String quantity, String reason) throws Exception {
        return owner.post("/api/v1/inventory/adjustments", "{\"productId\":\"" + widget + "\",\"warehouseId\":\"" + warehouse
                + "\",\"countedQuantity\":" + quantity + ",\"reason\":\"" + reason + "\"}");
    }

    private UUID warehouse(String code) throws Exception {
        return Api.id(owner.post("/api/v1/inventory/warehouses", "{\"code\":\"" + code + "\",\"name\":\"" + code + "\"}"));
    }

    private long audits(String action) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = ?", Long.class, action);
    }

    /** Every level's on-hand equals the sum of its ledger rows. */
    private void ledgerBalances() {
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("""
                select count(*) from stock_levels l
                where l.on_hand <> coalesce((select sum(m.quantity) from stock_movements m
                                             where m.product_id = l.product_id and m.warehouse_id = l.warehouse_id), 0)
                """, Long.class)).isZero();
    }

    @Test
    void productStockStartsAtZeroInEveryWarehouse() throws Exception {
        owner.get("/api/v1/inventory/stock/products/" + widget).andExpect(status().isOk())
                .andExpect(jsonPath("$.product.sku").value("W-1"))
                .andExpect(jsonPath("$.levels[*].warehouse.code").value(Matchers.contains("MAIN")))
                .andExpect(jsonPath("$.levels[0].onHand").value(0))
                .andExpect(jsonPath("$.available").value(0));
        owner.get("/api/v1/inventory/stock/products/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void countsStockAndWritesTheLedger() throws Exception {
        count(main, "10", "Opening stock").andExpect(status().isOk())
                .andExpect(jsonPath("$.levels[0].onHand").value(10.0))
                .andExpect(jsonPath("$.onHand").value(10.0));
        count(main, "4", "Damaged").andExpect(status().isOk()).andExpect(jsonPath("$.levels[0].onHand").value(4.0));
        owner.get("/api/v1/inventory/movements?productId=" + widget).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].kind").value("ADJUSTMENT"))
                .andExpect(jsonPath("$.items[0].quantity").value(-6.0))
                .andExpect(jsonPath("$.items[0].onHandAfter").value(4.0))
                .andExpect(jsonPath("$.items[0].reason").value("Damaged"))
                .andExpect(jsonPath("$.items[0].actor").exists())
                .andExpect(jsonPath("$.items[1].quantity").value(10.0));
        assertThat(audits("StockAdjusted")).isEqualTo(2);
        ledgerBalances();
    }

    @Test
    void countingTheSameQuantityChangesNothing() throws Exception {
        count(main, "5", "Count").andExpect(status().isOk());
        count(main, "5.0000", "Count again").andExpect(status().isOk());
        owner.get("/api/v1/inventory/movements?productId=" + widget).andExpect(jsonPath("$.total").value(1));
        assertThat(audits("StockAdjusted")).isEqualTo(1);
    }

    @Test
    void invalidCountsAreFieldErrors() throws Exception {
        UUID service = Api.id(owner.post("/api/v1/products", "{\"sku\":\"S-1\",\"name\":\"Setup\",\"kind\":\"SERVICE\"}"));
        String[][] cases = {
                {"{\"productId\":\"" + widget + "\",\"warehouseId\":\"" + main + "\",\"countedQuantity\":-1,\"reason\":\"x\"}", "countedQuantity"},
                {"{\"productId\":\"" + widget + "\",\"warehouseId\":\"" + main + "\",\"reason\":\"x\"}", "countedQuantity"},
                {"{\"productId\":\"" + widget + "\",\"warehouseId\":\"" + main + "\",\"countedQuantity\":1.23456,\"reason\":\"x\"}", "countedQuantity"},
                {"{\"productId\":\"" + widget + "\",\"warehouseId\":\"" + main + "\",\"countedQuantity\":1,\"reason\":\" \"}", "reason"},
                {"{\"productId\":\"" + UUID.randomUUID() + "\",\"warehouseId\":\"" + main + "\",\"countedQuantity\":1,\"reason\":\"x\"}", "productId"},
                {"{\"productId\":\"" + service + "\",\"warehouseId\":\"" + main + "\",\"countedQuantity\":1,\"reason\":\"x\"}", "productId"},
                {"{\"productId\":\"" + widget + "\",\"warehouseId\":\"" + UUID.randomUUID() + "\",\"countedQuantity\":1,\"reason\":\"x\"}", "warehouseId"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/inventory/adjustments", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
        owner.post("/api/v1/inventory/adjustments", cases[5][0])
                .andExpect(jsonPath("$.errors[0].message").value("Services don't carry stock."));
    }

    @Test
    void archivedWarehousesTakeNoStock() throws Exception {
        UUID spare = warehouse("SPARE");
        owner.post("/api/v1/inventory/warehouses/" + spare + "/archive", "").andExpect(status().isOk());
        count(spare, "1", "x").andExpect(status().isConflict());
        count(main, "5", "x").andExpect(status().isOk());
        owner.post("/api/v1/inventory/transfers", "{\"productId\":\"" + widget + "\",\"fromWarehouseId\":\"" + main
                + "\",\"toWarehouseId\":\"" + spare + "\",\"quantity\":1}").andExpect(status().isConflict());
        owner.post("/api/v1/products/" + widget + "/archive", "").andExpect(status().isOk());
        count(main, "3", "x").andExpect(status().isConflict());
        // the archived product's stock stays readable
        owner.get("/api/v1/inventory/stock/products/" + widget).andExpect(jsonPath("$.onHand").value(5.0));
    }

    @Test
    void unknownReferencesAreReportedBeforeArchivedOnes() throws Exception {
        owner.post("/api/v1/products/" + widget + "/archive", "").andExpect(status().isOk());
        count(UUID.randomUUID(), "1", "x").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("warehouseId"));
        UUID spare = warehouse("SPARE");
        UUID other = TestInventory.goods(owner, "W-2", "Gadget");
        owner.post("/api/v1/inventory/warehouses/" + spare + "/archive", "").andExpect(status().isOk());
        owner.post("/api/v1/inventory/transfers", "{\"productId\":\"" + other + "\",\"fromWarehouseId\":\"" + spare
                + "\",\"toWarehouseId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("toWarehouseId"));
    }

    @Test
    void transfersMoveAvailableStock() throws Exception {
        UUID pune = warehouse("PUNE");
        count(main, "10", "Opening stock").andExpect(status().isOk());
        owner.post("/api/v1/inventory/transfers", "{\"productId\":\"" + widget + "\",\"fromWarehouseId\":\"" + main
                        + "\",\"toWarehouseId\":\"" + pune + "\",\"quantity\":3,\"note\":\"For the Pune shop\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.levels[?(@.warehouse.code == 'MAIN')].onHand").value(Matchers.contains(7.0)))
                .andExpect(jsonPath("$.levels[?(@.warehouse.code == 'PUNE')].onHand").value(Matchers.contains(3.0)));
        List<String> refs = Api.read(owner.get("/api/v1/inventory/movements?productId=" + widget + "&kind=TRANSFER_OUT"),
                "$.items[*].referenceId");
        owner.get("/api/v1/inventory/movements?productId=" + widget + "&kind=TRANSFER_IN")
                .andExpect(jsonPath("$.items[0].referenceId").value(refs.getFirst()))
                .andExpect(jsonPath("$.items[0].quantity").value(3.0))
                .andExpect(jsonPath("$.items[0].reason").value("For the Pune shop"));
        owner.get("/api/v1/inventory/movements?warehouseId=" + pune).andExpect(jsonPath("$.total").value(1));
        assertThat(audits("StockTransferred")).isEqualTo(1);
        ledgerBalances();
    }

    @Test
    void transferShortagesAndSameWarehouseAreRefused() throws Exception {
        UUID pune = warehouse("PUNE");
        count(main, "10", "Opening stock").andExpect(status().isOk());
        owner.post("/api/v1/inventory/transfers", "{\"productId\":\"" + widget + "\",\"fromWarehouseId\":\"" + main
                        + "\",\"toWarehouseId\":\"" + pune + "\",\"quantity\":11}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Not enough stock."))
                .andExpect(jsonPath("$.shortages[0].requested").value(11))
                .andExpect(jsonPath("$.shortages[0].available").value(10.0))
                .andExpect(jsonPath("$.shortages[0].sku").value("W-1"));
        owner.post("/api/v1/inventory/transfers", "{\"productId\":\"" + widget + "\",\"fromWarehouseId\":\"" + main
                        + "\",\"toWarehouseId\":\"" + main + "\",\"quantity\":1}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("toWarehouseId"));
        owner.post("/api/v1/inventory/transfers", "{\"productId\":\"" + widget + "\",\"fromWarehouseId\":\"" + main
                        + "\",\"toWarehouseId\":\"" + pune + "\",\"quantity\":0}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("quantity"));
    }

    @Test
    void aWarehouseWithStockCannotBeArchived() throws Exception {
        UUID pune = warehouse("PUNE");
        count(pune, "2", "x").andExpect(status().isOk());
        owner.post("/api/v1/inventory/warehouses/" + pune + "/archive", "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Move or count out this warehouse's stock first."));
        count(pune, "0", "Moved out").andExpect(status().isOk());
        owner.post("/api/v1/inventory/warehouses/" + pune + "/archive", "").andExpect(status().isOk());
    }

    @Test
    void readersSeeStockButCannotChangeIt() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "Stock reader", "inventory.stock.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        reader.get("/api/v1/inventory/stock/products/" + widget).andExpect(status().isOk());
        reader.get("/api/v1/inventory/movements").andExpect(status().isOk());
        reader.post("/api/v1/inventory/adjustments", "{\"productId\":\"" + widget + "\",\"warehouseId\":\"" + main
                + "\",\"countedQuantity\":1,\"reason\":\"x\"}").andExpect(status().isForbidden());
        owner.get("/api/v1/inventory/movements?kind=SIDEWAYS").andExpect(status().isBadRequest());
    }
}
