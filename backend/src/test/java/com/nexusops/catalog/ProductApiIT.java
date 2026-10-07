package com.nexusops.catalog;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
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
class ProductApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("prod"));
        owner = Api.login(mvc, ws);
    }

    private UUID product(String json) throws Exception {
        return Api.id(owner.post("/api/v1/products", json).andExpect(status().isCreated()));
    }

    @Test
    void createsAProductWithDefaults() throws Exception {
        owner.post("/api/v1/products", "{\"sku\":\" WID-1 \",\"name\":\"Widget\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("WID-1"))
                .andExpect(jsonPath("$.name").value("Widget"))
                .andExpect(jsonPath("$.kind").value("GOODS"))
                .andExpect(jsonPath("$.unit").value("each"))
                .andExpect(jsonPath("$.listPrice").doesNotExist())
                .andExpect(jsonPath("$.currency").doesNotExist())
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void aPriceTakesTheWorkspaceCurrencyUnlessOneIsGiven() throws Exception {
        UUID widget = product("{\"sku\":\"W-1\",\"name\":\"Widget\",\"listPrice\":12.5}");
        owner.get("/api/v1/products/" + widget).andExpect(jsonPath("$.listPrice").value(12.5))
                .andExpect(jsonPath("$.currency").value("USD"));
        owner.post("/api/v1/products", "{\"sku\":\"S-1\",\"name\":\"Setup\",\"kind\":\"SERVICE\",\"unit\":\"hour\","
                        + "\"listPrice\":90,\"currency\":\"eur\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("SERVICE")).andExpect(jsonPath("$.unit").value("hour"))
                .andExpect(jsonPath("$.currency").value("EUR"));
        // a currency without a price is ignored
        owner.post("/api/v1/products", "{\"sku\":\"S-2\",\"name\":\"Free\",\"currency\":\"EUR\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.currency").doesNotExist());
    }

    @Test
    void invalidInputIsAFieldError() throws Exception {
        String[][] cases = {
                {"{\"sku\":\"has space\",\"name\":\"X\"}", "sku"},
                {"{\"sku\":\"\",\"name\":\"X\"}", "sku"},
                {"{\"sku\":\"A1\",\"name\":\" \"}", "name"},
                {"{\"sku\":\"A1\",\"name\":\"X\",\"listPrice\":-1}", "listPrice"},
                {"{\"sku\":\"A1\",\"name\":\"X\",\"listPrice\":1.23456}", "listPrice"},
                {"{\"sku\":\"A1\",\"name\":\"X\",\"listPrice\":1000000000000000}", "listPrice"},
                {"{\"sku\":\"A1\",\"name\":\"X\",\"listPrice\":1,\"currency\":\"EURO\"}", "currency"},
                {"{\"sku\":\"A1\",\"name\":\"X\",\"unit\":\"" + "u".repeat(21) + "\"}", "unit"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/products", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
    }

    @Test
    void skusAreUniqueIgnoringCaseIncludingArchivedProducts() throws Exception {
        UUID widget = product("{\"sku\":\"WID-1\",\"name\":\"Widget\"}");
        owner.post("/api/v1/products", "{\"sku\":\"wid-1\",\"name\":\"Other\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("sku"))
                .andExpect(jsonPath("$.errors[0].message").value("Another product already uses this SKU."));
        owner.post("/api/v1/products/" + widget + "/archive", "").andExpect(status().isOk());
        owner.post("/api/v1/products", "{\"sku\":\"WID-1\",\"name\":\"Other\"}").andExpect(status().isConflict());
    }

    @Test
    void updatesCheckVersionSkuAndArchive() throws Exception {
        UUID widget = product("{\"sku\":\"W-1\",\"name\":\"Widget\"}");
        product("{\"sku\":\"G-1\",\"name\":\"Gadget\"}");
        owner.put("/api/v1/products/" + widget, "{\"sku\":\"w-1\",\"name\":\"Widget XL\",\"listPrice\":5,\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.sku").value("w-1"))
                .andExpect(jsonPath("$.name").value("Widget XL")).andExpect(jsonPath("$.version").value(1));
        owner.put("/api/v1/products/" + widget, "{\"sku\":\"W-1\",\"name\":\"Old\",\"version\":0}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This record was changed by someone else. Reload and try again."));
        owner.put("/api/v1/products/" + widget, "{\"sku\":\"G-1\",\"name\":\"Clash\",\"version\":1}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errors[0].field").value("sku"));
        owner.put("/api/v1/products/" + widget, "{\"sku\":\"W-1\",\"name\":\"X\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("version"));
        owner.post("/api/v1/products/" + widget + "/archive", "").andExpect(jsonPath("$.archivedAt").exists());
        owner.put("/api/v1/products/" + widget, "{\"sku\":\"W-1\",\"name\":\"X\",\"version\":2}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("This record is archived."));
        owner.post("/api/v1/products/" + widget + "/restore", "").andExpect(jsonPath("$.archivedAt").doesNotExist());
        Long audited = OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where entity_id = ? and action in "
                        + "('ProductCreated','ProductUpdated','ProductArchived','ProductRestored')", Long.class,
                widget.toString());
        org.assertj.core.api.Assertions.assertThat(audited).isEqualTo(4);
    }

    @Test
    void listsFiltersAndSearches() throws Exception {
        product("{\"sku\":\"W-1\",\"name\":\"Widget\"}");
        product("{\"sku\":\"S-1\",\"name\":\"Setup\",\"kind\":\"SERVICE\"}");
        UUID old = product("{\"sku\":\"O-1\",\"name\":\"Old widget\"}");
        owner.post("/api/v1/products/" + old + "/archive", "");
        owner.get("/api/v1/products").andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[*].name", Matchers.contains("Setup", "Widget")));
        owner.get("/api/v1/products?kind=SERVICE").andExpect(jsonPath("$.items[*].sku", Matchers.contains("S-1")));
        owner.get("/api/v1/products?q=w-").andExpect(jsonPath("$.items[*].sku", Matchers.contains("W-1")));
        owner.get("/api/v1/products?q=widget&archived=true")
                .andExpect(jsonPath("$.items[*].sku", Matchers.contains("O-1")));
    }

    @Test
    void readersCannotWriteAndUnknownIdsAre404() throws Exception {
        UUID widget = product("{\"sku\":\"W-1\",\"name\":\"Widget\"}");
        UUID role = TestRoles.create(mvc, owner.session(), "Catalog reader", "catalog.product.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        reader.get("/api/v1/products/" + widget).andExpect(status().isOk());
        reader.get("/api/v1/products").andExpect(status().isOk());
        reader.post("/api/v1/products", "{\"sku\":\"X\",\"name\":\"X\"}").andExpect(status().isForbidden());
        reader.post("/api/v1/products/" + widget + "/archive", "").andExpect(status().isForbidden());
        owner.get("/api/v1/products/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }
}
