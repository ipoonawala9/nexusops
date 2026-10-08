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
class PurchaseOrderApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID supplier, widget, gadget, main;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("po"));
        owner = Api.login(mvc, ws);
        TestInventory.enable(owner);
        supplier = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Konkan Supplies\"}"));
        widget = TestInventory.goods(owner, "W-1", "Widget");
        gadget = TestInventory.goods(owner, "G-1", "Gadget");
        main = TestInventory.mainWarehouse(owner);
    }

    private String body(UUID warehouse, String lines) {
        return "{\"supplierId\":\"" + supplier + "\",\"warehouseId\":\"" + warehouse + "\",\"lines\":[" + lines + "]}";
    }

    private String line(UUID product, String quantity, String cost) {
        return "{\"productId\":\"" + product + "\",\"quantity\":" + quantity + ",\"unitCost\":" + cost + "}";
    }

    private UUID draft() throws Exception {
        return Api.id(owner.post("/api/v1/purchase-orders", body(main, line(widget, "10", "2.5") + "," + line(gadget, "4", "10")))
                .andExpect(status().isCreated()));
    }

    private ResultActions receive(UUID order, String lines, long version) throws Exception {
        return owner.post("/api/v1/purchase-orders/" + order + "/receipts", "{\"lines\":[" + lines + "],\"version\":" + version + "}");
    }

    private String lineIds(UUID order, int index) throws Exception {
        List<String> ids = Api.read(owner.get("/api/v1/purchase-orders/" + order), "$.lines[*].id");
        return ids.get(index);
    }

    private long audits(String action) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = ?", Long.class, action);
    }

    @Test
    void createsNumberedDrafts() throws Exception {
        owner.post("/api/v1/purchase-orders", body(main, line(widget, "10", "2.5") + "," + line(gadget, "4", "10")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value("PO-00001"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.supplier.name").value("Konkan Supplies"))
                .andExpect(jsonPath("$.warehouse.code").value("MAIN"))
                .andExpect(jsonPath("$.lines[*].lineNo").value(Matchers.contains(1, 2)))
                .andExpect(jsonPath("$.lines[0].product.sku").value("W-1"))
                .andExpect(jsonPath("$.lines[0].lineTotal").value(25.0))
                .andExpect(jsonPath("$.total").value(65.0));
        owner.post("/api/v1/purchase-orders", body(main, line(widget, "1", "2"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value("PO-00002"));
        // a draft doesn't make the party a supplier yet
        owner.get("/api/v1/parties/" + supplier).andExpect(jsonPath("$.roles", Matchers.empty()));
    }

    @Test
    void invalidOrdersAreFieldErrors() throws Exception {
        UUID service = Api.id(owner.post("/api/v1/products", "{\"sku\":\"S-1\",\"name\":\"Setup\",\"kind\":\"SERVICE\"}"));
        String[][] cases = {
                {body(main, ""), "lines"},
                {body(main, line(widget, "1", "1") + "," + line(widget, "2", "1")), "lines[1].productId"},
                {body(main, line(widget, "0", "1")), "lines[0].quantity"},
                {body(main, line(widget, "1", "-1")), "lines[0].unitCost"},
                {body(main, "{\"productId\":\"" + widget + "\",\"quantity\":1}"), "lines[0].unitCost"},
                {body(main, line(service, "1", "1")), "lines[0].productId"},
                {body(main, line(UUID.randomUUID(), "1", "1")), "lines[0].productId"},
                {body(UUID.randomUUID(), line(widget, "1", "1")), "warehouseId"},
                {"{\"warehouseId\":\"" + main + "\",\"lines\":[" + line(widget, "1", "1") + "]}", "supplierId"},
                {body(main, line(widget, "1", "1")).replace(supplier.toString(), UUID.randomUUID().toString()), "supplierId"},
                {body(main, line(widget, "1", "1")).replace("{\"supplierId\"", "{\"currency\":\"EURO\",\"supplierId\""), "currency"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/purchase-orders", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
    }

    @Test
    void unknownReferencesAreReportedBeforeArchivedOnes() throws Exception {
        UUID archived = TestInventory.goods(owner, "A-1", "Archived widget");
        owner.post("/api/v1/products/" + archived + "/archive", "").andExpect(status().isOk());
        owner.post("/api/v1/purchase-orders", body(UUID.randomUUID(), line(archived, "1", "1")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("warehouseId"));
        owner.post("/api/v1/purchase-orders", body(main, line(archived, "1", "1"))).andExpect(status().isConflict());
    }

    @Test
    void onlyDraftsAreEditedAndOrderingMakesTheSupplier() throws Exception {
        UUID order = draft();
        // no version in the body
        owner.put("/api/v1/purchase-orders/" + order, body(main, line(gadget, "3", "9")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("version"));
        owner.put("/api/v1/purchase-orders/" + order, "{\"supplierId\":\"" + supplier + "\",\"warehouseId\":\"" + main
                        + "\",\"expectedOn\":\"2026-11-15\",\"notes\":\"Call before delivery\",\"lines\":["
                        + line(gadget, "3", "9") + "],\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.expectedOn").value("2026-11-15")).andExpect(jsonPath("$.total").value(27.0));
        owner.post("/api/v1/purchase-orders/" + order + "/order", "{\"version\":0}").andExpect(status().isConflict());
        owner.post("/api/v1/purchase-orders/" + order + "/order", "{\"version\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ORDERED")).andExpect(jsonPath("$.orderedAt").exists());
        owner.get("/api/v1/parties/" + supplier).andExpect(jsonPath("$.roles[?(@.role == 'SUPPLIER')].status")
                .value(Matchers.contains("ACTIVE")));
        owner.put("/api/v1/purchase-orders/" + order, "{\"supplierId\":\"" + supplier + "\",\"warehouseId\":\"" + main
                + "\",\"lines\":[" + line(gadget, "1", "1") + "],\"version\":2}").andExpect(status().isConflict());
        owner.post("/api/v1/purchase-orders/" + order + "/order", "{\"version\":2}").andExpect(status().isConflict());
    }

    @Test
    void partialReceiptsCompleteTheOrderAndThenStop() throws Exception {
        UUID order = draft();
        owner.post("/api/v1/purchase-orders/" + order + "/order", "{\"version\":0}").andExpect(status().isOk());
        String first = lineIds(order, 0);
        String second = lineIds(order, 1);
        receive(order, "{\"lineId\":\"" + first + "\",\"quantity\":4}", 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_RECEIVED"))
                .andExpect(jsonPath("$.lines[0].receivedQuantity").value(4.0))
                .andExpect(jsonPath("$.lines[0].remainingQuantity").value(6.0));
        owner.get("/api/v1/inventory/stock/products/" + widget).andExpect(jsonPath("$.onHand").value(4.0));
        receive(order, "{\"lineId\":\"" + first + "\",\"quantity\":7}", 2).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("lines[0].quantity"));
        receive(order, "{\"lineId\":\"" + first + "\",\"quantity\":6},{\"lineId\":\"" + second + "\",\"quantity\":4}", 2)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.receivedAt").exists());
        receive(order, "{\"lineId\":\"" + first + "\",\"quantity\":1}", 3).andExpect(status().isConflict());
        owner.get("/api/v1/inventory/movements?productId=" + widget).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].kind").value("RECEIPT"))
                .andExpect(jsonPath("$.items[0].referenceType").value("PURCHASE_ORDER"))
                .andExpect(jsonPath("$.items[0].referenceId").value(order.toString()))
                .andExpect(jsonPath("$.items[0].reason").value("PO-00001"));
        owner.get("/api/v1/inventory/stock/products/" + gadget).andExpect(jsonPath("$.onHand").value(4.0));
        assertThat(audits("PurchaseOrderReceived")).isEqualTo(2);
    }

    @Test
    void receiptsNeedAnOrderedOrderAndItsOwnLines() throws Exception {
        UUID order = draft();
        String first = lineIds(order, 0);
        receive(order, "{\"lineId\":\"" + first + "\",\"quantity\":1}", 0).andExpect(status().isConflict());
        owner.post("/api/v1/purchase-orders/" + order + "/order", "{\"version\":0}").andExpect(status().isOk());
        receive(order, "{\"lineId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}", 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("lines[0].lineId"));
        receive(order, "", 1).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("lines"));
        receive(order, "{\"lineId\":\"" + first + "\",\"quantity\":1},{\"lineId\":\"" + first + "\",\"quantity\":1}", 1)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("lines[1].lineId"));
    }

    @Test
    void cancellation() throws Exception {
        UUID drafted = draft();
        owner.post("/api/v1/purchase-orders/" + drafted + "/cancel", "{\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.cancelledAt").exists());
        UUID ordered = draft();
        owner.post("/api/v1/purchase-orders/" + ordered + "/order", "{\"version\":0}").andExpect(status().isOk());
        owner.post("/api/v1/purchase-orders/" + ordered + "/cancel", "{\"version\":1}").andExpect(status().isOk());
        UUID received = draft();
        owner.post("/api/v1/purchase-orders/" + received + "/order", "{\"version\":0}").andExpect(status().isOk());
        receive(received, "{\"lineId\":\"" + lineIds(received, 0) + "\",\"quantity\":1}", 1).andExpect(status().isOk());
        owner.post("/api/v1/purchase-orders/" + received + "/cancel", "{\"version\":2}").andExpect(status().isConflict());
        assertThat(audits("PurchaseOrderCancelled")).isEqualTo(2);
    }

    @Test
    void aWarehouseWithOpenPurchaseOrdersCannotBeArchived() throws Exception {
        UUID pune = Api.id(owner.post("/api/v1/inventory/warehouses", "{\"code\":\"PUNE\",\"name\":\"Pune\"}"));
        UUID order = Api.id(owner.post("/api/v1/purchase-orders", body(pune, line(widget, "1", "1"))));
        owner.post("/api/v1/inventory/warehouses/" + pune + "/archive", "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Close or move this warehouse's open orders first."));
        owner.post("/api/v1/purchase-orders/" + order + "/cancel", "{\"version\":0}").andExpect(status().isOk());
        owner.post("/api/v1/inventory/warehouses/" + pune + "/archive", "").andExpect(status().isOk());
    }

    @Test
    void listsAndFilters() throws Exception {
        UUID first = draft();
        UUID second = draft();
        owner.post("/api/v1/purchase-orders/" + second + "/order", "{\"version\":0}").andExpect(status().isOk());
        owner.get("/api/v1/purchase-orders").andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].number").value("PO-00002"))
                .andExpect(jsonPath("$.items[0].lineCount").value(2))
                .andExpect(jsonPath("$.items[0].total").value(65.0));
        owner.get("/api/v1/purchase-orders?status=ORDERED").andExpect(jsonPath("$.items[*].id").value(Matchers.contains(second.toString())));
        owner.get("/api/v1/purchase-orders?q=00001").andExpect(jsonPath("$.items[*].id").value(Matchers.contains(first.toString())));
        owner.get("/api/v1/purchase-orders?supplierId=" + supplier).andExpect(jsonPath("$.total").value(2));
        owner.get("/api/v1/purchase-orders?warehouseId=" + main).andExpect(jsonPath("$.total").value(2));
        owner.get("/api/v1/purchase-orders/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void permissionsAndRecordPanels() throws Exception {
        UUID order = draft();
        owner.post("/api/v1/activities", "{\"subjectType\":\"PURCHASE_ORDER\",\"subjectId\":\"" + order
                + "\",\"type\":\"NOTE\",\"summary\":\"Asked for delivery on Friday\"}").andExpect(status().isCreated());
        UUID readerRole = TestRoles.create(mvc, owner.session(), "PO reader", "inventory.purchase.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(readerRole)));
        reader.get("/api/v1/purchase-orders/" + order).andExpect(status().isOk());
        reader.post("/api/v1/purchase-orders", body(main, line(widget, "1", "1"))).andExpect(status().isForbidden());
        UUID blindRole = TestRoles.create(mvc, owner.session(), "Buyer without directory", "inventory.purchase.read",
                "inventory.purchase.manage");
        Api blind = Api.login(mvc, members.create(ws.tenantId(), Set.of(blindRole)));
        blind.post("/api/v1/purchase-orders", body(main, line(widget, "1", "1"))).andExpect(status().isForbidden());
        assertThat(audits("PurchaseOrderCreated")).isEqualTo(1);
    }
}
