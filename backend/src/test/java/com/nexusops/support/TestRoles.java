package com.nexusops.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

public final class TestRoles {

    private TestRoles() {}

    public static UUID create(MockMvc mvc, TestTenants.Session session, String name, String... permissions) throws Exception {
        String perms = Arrays.stream(permissions).map(p -> "\"" + p + "\"").collect(Collectors.joining(","));
        String body = mvc.perform(post("/api/v1/roles").header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"permissions\":[" + perms + "]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    public static UUID system(UUID tenantId, String name) {
        return OwnerJdbc.ownerAs(tenantId).queryForObject("select id from roles where name = ?", UUID.class, name);
    }
}
