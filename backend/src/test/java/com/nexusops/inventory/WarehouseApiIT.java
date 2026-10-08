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
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class WarehouseApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("wh"));
        owner = Api.login(mvc, ws);
        TestInventory.enable(owner);
    }

    @Test
    void aNewWorkspaceHasAMainWarehouse() throws Exception {
        owner.get("/api/v1/inventory/warehouses").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code").value(Matchers.contains("MAIN")))
                .andExpect(jsonPath("$[0].name").value("Main warehouse"));
    }

    @Test
    void inventoryRoutesNeedTheModule() throws Exception {
        Api other = Api.login(mvc, TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("nowh")));
        other.get("/api/v1/inventory/warehouses").andExpect(status().isForbidden());
    }

    @Test
    void ownersHoldTheInventoryPermissionsAndTheUnusedSeedIsGone() throws Exception {
        owner.get("/api/v1/me").andExpect(jsonPath("$.permissions", Matchers.hasItems("inventory.stock.read",
                "inventory.stock.adjust", "inventory.warehouse.manage", "inventory.purchase.read",
                "inventory.purchase.manage", "inventory.order.read", "inventory.order.manage",
                "inventory.reorder.manage")));
        owner.get("/api/v1/permissions").andExpect(jsonPath("$[*].code",
                Matchers.not(Matchers.hasItem("inventory.product.read"))));
    }

    @Test
    void createsRenamesArchivesAndRestores() throws Exception {
        UUID pune = Api.id(owner.post("/api/v1/inventory/warehouses",
                "{\"code\":\" pune-1 \",\"name\":\" Pune   store \",\"address\":\"Hadapsar, Pune\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("pune-1"))
                .andExpect(jsonPath("$.name").value("Pune store")));
        owner.put("/api/v1/inventory/warehouses/" + pune, "{\"code\":\"PUNE-1\",\"name\":\"Pune\",\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("PUNE-1"))
                .andExpect(jsonPath("$.address").doesNotExist());
        owner.post("/api/v1/inventory/warehouses/" + pune + "/archive", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").exists());
        owner.get("/api/v1/inventory/warehouses").andExpect(jsonPath("$[*].code").value(Matchers.contains("MAIN")));
        owner.get("/api/v1/inventory/warehouses?archived=true").andExpect(jsonPath("$[*].code").value(Matchers.contains("PUNE-1")));
        owner.put("/api/v1/inventory/warehouses/" + pune, "{\"code\":\"P\",\"name\":\"P\",\"version\":2}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("This record is archived."));
        owner.post("/api/v1/inventory/warehouses/" + pune + "/restore", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").doesNotExist());
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select count(*) from audit_events where action in "
                + "('WarehouseCreated','WarehouseUpdated','WarehouseArchived','WarehouseRestored')", Long.class))
                .isEqualTo(4);
    }

    @Test
    void invalidWarehousesAreFieldErrors() throws Exception {
        String[][] cases = {
                {"{\"code\":\"\",\"name\":\"X\"}", "code"},
                {"{\"code\":\"has space\",\"name\":\"X\"}", "code"},
                {"{\"code\":\"" + "A".repeat(21) + "\",\"name\":\"X\"}", "code"},
                {"{\"code\":\"OK\",\"name\":\" \"}", "name"},
                {"{\"code\":\"OK\",\"name\":\"X\",\"address\":\"" + "a".repeat(501) + "\"}", "address"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/inventory/warehouses", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
        owner.post("/api/v1/inventory/warehouses", "{\"code\":\"main\",\"name\":\"Again\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("code"));
        UUID main = TestInventory.mainWarehouse(owner);
        owner.put("/api/v1/inventory/warehouses/" + main, "{\"code\":\"MAIN\",\"name\":\"Main\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("version"));
    }

    @Test
    void theLastActiveWarehouseStays() throws Exception {
        UUID main = TestInventory.mainWarehouse(owner);
        owner.post("/api/v1/inventory/warehouses/" + main + "/archive", "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Keep at least one active warehouse."));
        owner.post("/api/v1/inventory/warehouses/" + UUID.randomUUID() + "/archive", "").andExpect(status().isNotFound());
    }

    @Test
    void readersSeeWarehousesButCannotChangeThem() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "Stock reader", "inventory.stock.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        reader.get("/api/v1/inventory/warehouses").andExpect(status().isOk());
        reader.post("/api/v1/inventory/warehouses", "{\"code\":\"X\",\"name\":\"X\"}").andExpect(status().isForbidden());
    }

    @Test
    void managersWithoutStockReadStillListWarehouses() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "Warehouse keeper", "inventory.warehouse.manage");
        Api keeper = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        keeper.get("/api/v1/inventory/warehouses").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code").value(Matchers.contains("MAIN")));
        keeper.get("/api/v1/inventory/stock").andExpect(status().isForbidden());
    }
}
