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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class SalesOrderApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID customer, widget, gadget, main;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("so"));
        owner = Api.login(mvc, ws);
        TestInventory.enable(owner);
        customer = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Deccan Retail\"}"));
        widget = TestInventory.goods(owner, "W-1", "Widget");
        gadget = TestInventory.goods(owner, "G-1", "Gadget");
        main = TestInventory.mainWarehouse(owner);
        owner.put("/api/v1/products/" + widget, "{\"sku\":\"W-1\",\"name\":\"Widget\",\"kind\":\"GOODS\","
                + "\"listPrice\":12.5,\"currency\":\"USD\",\"version\":0}").andExpect(status().isOk());
        // TestInventory.goods gives a list price; the gadget has none, so it must be priced on every line
        owner.put("/api/v1/products/" + gadget, "{\"sku\":\"G-1\",\"name\":\"Gadget\",\"kind\":\"GOODS\","
                + "\"version\":0}").andExpect(status().isOk());
        count(widget, "10");
        count(gadget, "3");
    }

    private void count(UUID product, String quantity) throws Exception {
        owner.post("/api/v1/inventory/adjustments", "{\"productId\":\"" + product + "\",\"warehouseId\":\"" + main
                + "\",\"countedQuantity\":" + quantity + ",\"reason\":\"Opening count\"}").andExpect(status().isOk());
    }

    private String body(String lines) {
        return "{\"customerId\":\"" + customer + "\",\"warehouseId\":\"" + main + "\",\"lines\":[" + lines + "]}";
    }

    private String line(UUID product, String quantity, String price) {
        return "{\"productId\":\"" + product + "\",\"quantity\":" + quantity
                + (price == null ? "" : ",\"unitPrice\":" + price) + "}";
    }

    private UUID draft(String lines) throws Exception {
        return Api.id(owner.post("/api/v1/sales-orders", body(lines)).andExpect(status().isCreated()));
    }

    private ResultActions act(UUID order, String action, long version) throws Exception {
        return owner.post("/api/v1/sales-orders/" + order + "/" + action, "{\"version\":" + version + "}");
    }

    private ResultActions stock(UUID product) throws Exception {
        return owner.get("/api/v1/inventory/stock/products/" + product);
    }

    private long audits(String action) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = ?", Long.class, action);
    }

    @Test
    void draftsAreNumberedAndPricedFromTheCatalog() throws Exception {
        owner.post("/api/v1/sales-orders", body(line(widget, "2", null) + "," + line(gadget, "1", "40")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value("SO-00001"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.customer.name").value("Deccan Retail"))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(12.5))
                .andExpect(jsonPath("$.lines[0].lineTotal").value(25.0))
                .andExpect(jsonPath("$.total").value(65.0));
        // a draft reserves nothing and marks nobody a customer
        stock(widget).andExpect(jsonPath("$.reserved").value(0.0));
        owner.get("/api/v1/parties/" + customer).andExpect(jsonPath("$.roles", Matchers.empty()));
    }

    @Test
    void invalidOrdersAreFieldErrors() throws Exception {
        String eur = body(line(widget, "1", null)).replace("{\"customerId\"", "{\"currency\":\"EUR\",\"customerId\"");
        String[][] cases = {
                {body(""), "lines"},
                {body(line(gadget, "1", null)), "lines[0].unitPrice"},
                {eur, "lines[0].unitPrice"},
                {body(line(widget, "0", "1")), "lines[0].quantity"},
                {body(line(widget, "1", "-1")), "lines[0].unitPrice"},
                {body(line(widget, "1", "1") + "," + line(widget, "1", "1")), "lines[1].productId"},
                {body(line(UUID.randomUUID(), "1", "1")), "lines[0].productId"},
                {"{\"warehouseId\":\"" + main + "\",\"lines\":[" + line(widget, "1", "1") + "]}", "customerId"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/sales-orders", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
        owner.post("/api/v1/sales-orders", body("null")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("lines[0]"))
                .andExpect(jsonPath("$.errors[0].message").value("Add a product and quantity."));
        UUID order = draft(line(widget, "1", null));
        owner.put("/api/v1/sales-orders/" + order, body(line(widget, "1", null) + ",null")
                .replace("]}", "],\"version\":0}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("lines[1]"))
                .andExpect(jsonPath("$.errors[0].message").value("Add a product and quantity."));
    }

    @Test
    void confirmingReservesAndMakesTheCustomer() throws Exception {
        UUID order = draft(line(widget, "4", null) + "," + line(gadget, "3", "40"));
        act(order, "confirm", 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED")).andExpect(jsonPath("$.confirmedAt").exists());
        stock(widget).andExpect(jsonPath("$.onHand").value(10.0)).andExpect(jsonPath("$.reserved").value(4.0))
                .andExpect(jsonPath("$.available").value(6.0));
        stock(gadget).andExpect(jsonPath("$.available").value(0.0));
        owner.get("/api/v1/parties/" + customer).andExpect(jsonPath("$.roles[?(@.role == 'CUSTOMER')].status")
                .value(Matchers.contains("ACTIVE")));
        owner.put("/api/v1/sales-orders/" + order, body(line(widget, "1", null)).replace("]}", "],\"version\":1}"))
                .andExpect(status().isConflict());
        act(order, "confirm", 1).andExpect(status().isConflict());
    }

    @Test
    void shortagesRefuseTheWholeConfirmation() throws Exception {
        UUID order = draft(line(widget, "4", null) + "," + line(gadget, "5", "40"));
        act(order, "confirm", 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Not enough stock."))
                .andExpect(jsonPath("$.shortages.length()").value(1))
                .andExpect(jsonPath("$.shortages[0].sku").value("G-1"))
                .andExpect(jsonPath("$.shortages[0].requested").value(5.0))
                .andExpect(jsonPath("$.shortages[0].available").value(3.0));
        stock(widget).andExpect(jsonPath("$.reserved").value(0.0));
        owner.get("/api/v1/sales-orders/" + order).andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void fulfilmentIssuesStockAndCancellationReleasesIt() throws Exception {
        UUID shipped = draft(line(widget, "4", null));
        act(shipped, "fulfil", 0).andExpect(status().isConflict());
        act(shipped, "confirm", 0).andExpect(status().isOk());
        act(shipped, "fulfil", 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FULFILLED")).andExpect(jsonPath("$.fulfilledAt").exists());
        stock(widget).andExpect(jsonPath("$.onHand").value(6.0)).andExpect(jsonPath("$.reserved").value(0.0));
        owner.get("/api/v1/inventory/movements?productId=" + widget)
                .andExpect(jsonPath("$.items[0].kind").value("ISSUE"))
                .andExpect(jsonPath("$.items[0].quantity").value(-4.0))
                .andExpect(jsonPath("$.items[0].referenceType").value("SALES_ORDER"))
                .andExpect(jsonPath("$.items[0].reason").value("SO-00001"));
        act(shipped, "cancel", 2).andExpect(status().isConflict());

        UUID held = draft(line(widget, "5", null));
        act(held, "confirm", 0).andExpect(status().isOk());
        stock(widget).andExpect(jsonPath("$.reserved").value(5.0));
        act(held, "cancel", 1).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        stock(widget).andExpect(jsonPath("$.reserved").value(0.0)).andExpect(jsonPath("$.onHand").value(6.0));
        UUID dropped = draft(line(widget, "1", null));
        act(dropped, "cancel", 0).andExpect(status().isOk());
        assertThat(audits("SalesOrderFulfilled")).isEqualTo(1);
        assertThat(audits("SalesOrderCancelled")).isEqualTo(2);
        TestInventory.assertLedgerBalances(ws.tenantId());
    }

    @Test
    void reservedStockCannotBeCountedAwayOrTransferred() throws Exception {
        UUID order = draft(line(widget, "8", null));
        act(order, "confirm", 0).andExpect(status().isOk());
        owner.post("/api/v1/inventory/adjustments", "{\"productId\":\"" + widget + "\",\"warehouseId\":\"" + main
                + "\",\"countedQuantity\":7,\"reason\":\"Recount\"}").andExpect(status().isConflict());
        UUID pune = Api.id(owner.post("/api/v1/inventory/warehouses", "{\"code\":\"PUNE\",\"name\":\"Pune\"}"));
        owner.post("/api/v1/inventory/transfers", "{\"productId\":\"" + widget + "\",\"fromWarehouseId\":\"" + main
                + "\",\"toWarehouseId\":\"" + pune + "\",\"quantity\":3}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.shortages[0].available").value(2.0));
    }

    @Test
    void anArchivedProductOnAnExistingLineDoesNotBlockTheOrder() throws Exception {
        UUID order = draft(line(widget, "2", null));
        owner.post("/api/v1/products/" + widget + "/archive", "").andExpect(status().isOk());
        owner.post("/api/v1/sales-orders", body(line(widget, "1", "1"))).andExpect(status().isConflict());
        owner.put("/api/v1/sales-orders/" + order, body(line(widget, "3", "11")).replace("]}", "],\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].quantity").value(3.0));
        act(order, "confirm", 1).andExpect(status().isOk());
        act(order, "fulfil", 2).andExpect(status().isOk());
        stock(widget).andExpect(jsonPath("$.onHand").value(7.0));
    }

    @Test
    void anArchivedNewProductDoesNotMaskAnUnknownWarehouse() throws Exception {
        owner.post("/api/v1/products/" + widget + "/archive", "").andExpect(status().isOk());
        String unknown = body(line(widget, "1", "1")).replace(main.toString(), UUID.randomUUID().toString());
        owner.post("/api/v1/sales-orders", unknown).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("warehouseId"));
    }

    private List<Integer> concurrently(java.util.concurrent.Callable<Integer> first,
            java.util.concurrent.Callable<Integer> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (var call : List.of(first, second)) {
                results.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentFulfilmentsOfOneOrderGiveOneSuccess() throws Exception {
        UUID order = draft(line(widget, "4", null));
        act(order, "confirm", 0).andExpect(status().isOk());
        List<Integer> statuses = concurrently(
                () -> act(order, "fulfil", 1).andReturn().getResponse().getStatus(),
                () -> act(order, "fulfil", 1).andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        stock(widget).andExpect(jsonPath("$.onHand").value(6.0)).andExpect(jsonPath("$.reserved").value(0.0));

        TestInventory.assertLedgerBalances(ws.tenantId());
    }

    @Test
    void concurrentCancellationsOfOneOrderGiveOneSuccess() throws Exception {
        UUID order = draft(line(widget, "4", null));
        UUID other = draft(line(widget, "3", null));
        act(order, "confirm", 0).andExpect(status().isOk());
        act(other, "confirm", 0).andExpect(status().isOk());
        List<Integer> statuses = concurrently(
                () -> act(order, "cancel", 1).andReturn().getResponse().getStatus(),
                () -> act(order, "cancel", 1).andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        // only the cancelled order's 4 were released; the other order's reservation stands
        stock(widget).andExpect(jsonPath("$.onHand").value(10.0)).andExpect(jsonPath("$.reserved").value(3.0));

        TestInventory.assertLedgerBalances(ws.tenantId());
    }

    @Test
    void concurrentConfirmationsNeverOversell() throws Exception {
        UUID first = draft(line(gadget, "2", "40"));
        UUID second = draft(line(gadget, "2", "40"));
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (UUID order : List.of(first, second)) {
                results.add(pool.submit(() -> {
                    start.await();
                    return act(order, "confirm", 0).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        stock(gadget).andExpect(jsonPath("$.reserved").value(2.0)).andExpect(jsonPath("$.available").value(1.0));

        TestInventory.assertLedgerBalances(ws.tenantId());
    }

    @Test
    void aWarehouseWithOpenSalesOrdersCannotBeArchived() throws Exception {
        UUID pune = Api.id(owner.post("/api/v1/inventory/warehouses", "{\"code\":\"PUNE\",\"name\":\"Pune\"}"));
        UUID order = Api.id(owner.post("/api/v1/sales-orders", body(line(widget, "1", null))
                .replace(main.toString(), pune.toString())).andExpect(status().isCreated()));
        owner.post("/api/v1/inventory/warehouses/" + pune + "/archive", "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Close or move this warehouse's open orders first."));
        act(order, "cancel", 0).andExpect(status().isOk());
        owner.post("/api/v1/inventory/warehouses/" + pune + "/archive", "").andExpect(status().isOk());
    }

    @Test
    void listsFiltersAndPermissions() throws Exception {
        UUID first = draft(line(widget, "1", null));
        UUID second = draft(line(widget, "1", null) + "," + line(gadget, "1", "40"));
        act(second, "confirm", 0).andExpect(status().isOk());
        owner.get("/api/v1/sales-orders").andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].number").value("SO-00002"))
                .andExpect(jsonPath("$.items[0].lineCount").value(2));
        owner.get("/api/v1/sales-orders?status=CONFIRMED")
                .andExpect(jsonPath("$.items[*].id").value(Matchers.contains(second.toString())));
        owner.get("/api/v1/sales-orders?q=00001")
                .andExpect(jsonPath("$.items[*].id").value(Matchers.contains(first.toString())));
        owner.get("/api/v1/sales-orders?q=deccan").andExpect(jsonPath("$.total").value(2));
        UUID other = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Malabar Traders\"}"));
        UUID third = Api.id(owner.post("/api/v1/sales-orders", body(line(widget, "1", null))
                .replace(customer.toString(), other.toString())).andExpect(status().isCreated()));
        owner.get("/api/v1/sales-orders?q=bar trad").andExpect(jsonPath("$.items[*].id")
                .value(Matchers.contains(third.toString())));
        owner.get("/api/v1/sales-orders?customerId=" + customer).andExpect(jsonPath("$.total").value(2));
        owner.get("/api/v1/sales-orders/" + UUID.randomUUID()).andExpect(status().isNotFound());

        UUID readerRole = TestRoles.create(mvc, owner.session(), "Order reader", "inventory.order.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(readerRole)));
        reader.get("/api/v1/sales-orders/" + first).andExpect(status().isOk());
        reader.post("/api/v1/sales-orders/" + first + "/confirm", "{\"version\":0}").andExpect(status().isForbidden());
        owner.post("/api/v1/activities", "{\"subjectType\":\"SALES_ORDER\",\"subjectId\":\"" + first
                + "\",\"type\":\"NOTE\",\"summary\":\"Customer wants it gift-wrapped\"}").andExpect(status().isCreated());
        owner.get("/api/v1/activities?subjectType=PARTY&subjectId=" + customer + "&includeRelated=true")
                .andExpect(jsonPath("$.items[*].summary").value(Matchers.hasItem("Customer wants it gift-wrapped")));
        assertThat(audits("SalesOrderCreated")).isEqualTo(3);
        assertThat(audits("SalesOrderConfirmed")).isEqualTo(1);
    }
}
