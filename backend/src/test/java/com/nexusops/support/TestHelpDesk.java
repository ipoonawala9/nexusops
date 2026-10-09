package com.nexusops.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

/** Enables the HelpDesk module and looks up its seeded records for tests. */
public final class TestHelpDesk {

    private TestHelpDesk() {}

    public static void enable(Api owner) throws Exception {
        owner.put("/api/v1/tenant/modules/HELPDESK", "{\"enabled\":true}").andExpect(status().isOk());
    }

    public static UUID category(Api owner, String name) throws Exception {
        List<String> ids = Api.read(owner.get("/api/v1/helpdesk/categories"), "$[?(@.name == '" + name + "')].id");
        return UUID.fromString(ids.getFirst());
    }
}
