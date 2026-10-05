package com.nexusops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.identity.security.PrincipalStateCache;
import com.nexusops.shared.TenantContext;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Session;
import com.nexusops.support.TestTenants.Workspace;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Blueprint Phase 2/3 exit criterion: Tenant A and B coexist; A cannot reach B. */
@AutoConfigureMockMvc
class TenantIsolationIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PrincipalStateCache principalCache;

    Workspace a;
    Workspace b;
    Session sessionA;
    Session sessionB;

    @BeforeEach
    void twoTenants() throws Exception {
        a = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("iso-a"));
        b = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("iso-b"));
        sessionA = TestTenants.login(mvc, a);
        sessionB = TestTenants.login(mvc, b);
    }

    private String bearer(Session s) {
        return "Bearer " + s.accessToken();
    }

    @Test
    void eachTenantSeesOnlyItsOwnSettings() throws Exception {
        mvc.perform(get("/api/v1/tenant").header("Authorization", bearer(sessionA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.slug").value(a.slug()));
        mvc.perform(get("/api/v1/tenant").header("Authorization", bearer(sessionB)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.slug").value(b.slug()));
    }

    @Test
    void updatingOneTenantNeverTouchesTheOther() throws Exception {
        mvc.perform(patch("/api/v1/tenant").header("Authorization", bearer(sessionA))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Renamed A\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Renamed A"));
        assertThat(OwnerJdbc.jdbc().queryForObject("select name from tenants where id = ?", String.class, b.tenantId()))
                .isEqualTo(b.slug() + " Inc");
    }

    @Test
    void refreshTokenOfBCannotBeReplayedUnderTenantA() throws Exception {
        String forged = a.tenantId() + sessionB.refreshToken().substring(36);
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("nexus_rt", forged)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void applicationConnectionsBoundToACannotSeeAnyRowOfB() {
        for (String table : RlsCoverageIT.EXPECTED_TENANT_TABLES) {
            String predicate = switch (table) {
                case "role_permissions" -> "role_id in (select id from roles where tenant_id = ?)";
                case "user_roles" -> "user_id in (select id from users where tenant_id = ?)";
                default -> "tenant_id = ?";
            };
            Long count = TenantContext.callAs(a.tenantId(),
                    () -> jdbc.queryForObject("select count(*) from " + table + " where " + predicate, Long.class, b.tenantId()));
            assertThat(count).as(table).isZero();
        }
        Long ownRows = TenantContext.callAs(a.tenantId(),
                () -> jdbc.queryForObject("select count(*) from users", Long.class));
        assertThat(ownRows).isOne();
    }

    @Test
    void principalCacheKeysAreTenantPrefixed() throws Exception {
        mvc.perform(get("/api/v1/me").header("Authorization", bearer(sessionA))).andExpect(status().isOk());
        assertThat(redis.keys("tenant:" + a.tenantId() + ":user:*:principal")).hasSize(1);
        assertThat(redis.keys("*principal*")).allMatch(k -> k.startsWith("tenant:"));
    }

    @Test
    void permissionsComeFromTheServerNotTheToken() throws Exception {
        UUID userA = OwnerJdbc.ownerAs(a.tenantId()).queryForObject("select id from users", UUID.class);
        OwnerJdbc.ownerAs(a.tenantId()).update("delete from user_roles where user_id = ?", userA);
        principalCache.evict(a.tenantId(), userA);
        mvc.perform(get("/api/v1/tenant").header("Authorization", bearer(sessionA)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }
}
