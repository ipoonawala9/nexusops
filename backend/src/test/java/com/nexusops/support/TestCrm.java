package com.nexusops.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Enables the CRM module for a workspace (permissions of module CRM are inert until it is). */
public final class TestCrm {

    private TestCrm() {}

    public static void enable(Api owner) throws Exception {
        owner.put("/api/v1/tenant/modules/CRM", "{\"enabled\":true}").andExpect(status().isOk());
    }
}
