package com.nexusops.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestInventory;
import com.nexusops.support.TestTenants;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Disabling Inventory removes every Inventory permission, so every Inventory route answers 403 (criterion 4). */
@AutoConfigureMockMvc
class InventoryModuleGateIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Test
    void everyInventoryHandlerRequiresAnInventoryPermission() {
        List<String> violations = new ArrayList<>();
        Set<String> checked = new HashSet<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            for (String path : info.getPatternValues()) {
                if (!(path.startsWith("/api/v1/inventory/") || path.startsWith("/api/v1/purchase-orders")
                        || path.startsWith("/api/v1/sales-orders"))) {
                    continue;
                }
                var preAuthorize = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
                if (preAuthorize == null) {
                    preAuthorize = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
                }
                String expression = preAuthorize == null ? "" : preAuthorize.value().replace(" ", "");
                checked.add(path);
                if (!(expression.contains("hasAuthority('inventory.")
                        || expression.contains("hasAnyAuthority('inventory."))) {
                    violations.add(info.getMethodsCondition() + " " + path + " -> " + expression);
                }
            }
        });
        assertThat(checked).as("Inventory routes were found").hasSizeGreaterThanOrEqualTo(20);
        assertThat(violations).isEmpty();
    }

    @Test
    void everyInventoryRouteIsForbiddenWhileTheModuleIsOff() throws Exception {
        Api owner = Api.login(mvc, TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("igate")));
        TestInventory.enable(owner);
        UUID main = TestInventory.mainWarehouse(owner);
        UUID widget = TestInventory.goods(owner, "G-1", "Gate widget");
        owner.put("/api/v1/tenant/modules/INVENTORY", "{\"enabled\":false}").andExpect(status().isOk());
        UUID any = UUID.randomUUID();
        owner.get("/api/v1/inventory/warehouses").andExpect(status().isForbidden());
        owner.post("/api/v1/inventory/warehouses", "{\"code\":\"X\",\"name\":\"X\"}").andExpect(status().isForbidden());
        owner.post("/api/v1/inventory/warehouses/" + main + "/archive", "").andExpect(status().isForbidden());
        owner.get("/api/v1/inventory/stock").andExpect(status().isForbidden());
        owner.get("/api/v1/inventory/stock/products/" + widget).andExpect(status().isForbidden());
        owner.get("/api/v1/inventory/movements").andExpect(status().isForbidden());
        owner.post("/api/v1/inventory/adjustments", "{}").andExpect(status().isForbidden());
        owner.post("/api/v1/inventory/transfers", "{}").andExpect(status().isForbidden());
        owner.get("/api/v1/inventory/overview").andExpect(status().isForbidden());
        owner.get("/api/v1/inventory/reorder-rules").andExpect(status().isForbidden());
        owner.put("/api/v1/inventory/reorder-rules", "{}").andExpect(status().isForbidden());
        owner.get("/api/v1/inventory/reorder-suggestions").andExpect(status().isForbidden());
        owner.post("/api/v1/inventory/reorder-suggestions/purchase-orders", "{}").andExpect(status().isForbidden());
        owner.get("/api/v1/purchase-orders").andExpect(status().isForbidden());
        owner.post("/api/v1/purchase-orders", "{}").andExpect(status().isForbidden());
        owner.post("/api/v1/purchase-orders/" + any + "/receipts", "{}").andExpect(status().isForbidden());
        owner.get("/api/v1/sales-orders").andExpect(status().isForbidden());
        owner.post("/api/v1/sales-orders/" + any + "/confirm", "{}").andExpect(status().isForbidden());
        TestInventory.enable(owner);
        owner.get("/api/v1/inventory/warehouses").andExpect(status().isOk());
    }
}
