package com.nexusops;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.identity.security.AccessTokenService;
import com.nexusops.identity.security.JwtKeyConfig;
import com.nexusops.identity.security.JwtProperties;
import com.nexusops.identity.security.PrincipalStateCache;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class TokenMisuseIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired AccessTokenService accessTokens;
    @Autowired JwtProperties jwtProperties;
    @Autowired PrincipalStateCache principalCache;

    Workspace ws;
    Session session;
    UUID userId;

    @BeforeEach
    void login() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("misuse"));
        session = TestTenants.login(mvc, ws);
        userId = OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select id from users", UUID.class);
    }

    private ResultActions me(String token) throws Exception {
        return mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token));
    }

    @Test
    void tamperedSignatureIsRejected() throws Exception {
        String token = session.accessToken();
        char last = token.charAt(token.length() - 2);
        String tampered = token.substring(0, token.length() - 2) + (last == 'A' ? 'B' : 'A') + token.charAt(token.length() - 1);
        me(tampered).andExpect(status().isUnauthorized());
        me("not.a.jwt").andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedWithAForeignKeyIsRejected() throws Exception {
        var foreign = new AccessTokenService(JwtKeyConfig.encoder(JwtKeyConfig.rsaKey(jwtProperties)), jwtProperties);
        me(foreign.issue(ws.tenantId(), userId, 0).value()).andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenForAUserOfAnotherTenantIsRejected() throws Exception {
        Workspace other = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("misuse-b"));
        // correctly signed, but tid points at a tenant where this user does not exist
        me(accessTokens.issue(other.tenantId(), userId, 0).value()).andExpect(status().isUnauthorized());
        me(accessTokens.issue(ws.tenantId(), UUID.randomUUID(), 0).value()).andExpect(status().isUnauthorized());
    }

    @Test
    void staleTokenVersionRejectedAfterLogoutAll() throws Exception {
        mvc.perform(post("/api/v1/auth/logout-all").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isNoContent());
        me(session.accessToken()).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("nexus_rt", session.refreshToken())))
                .andExpect(status().isUnauthorized());
        me(TestTenants.login(mvc, ws).accessToken()).andExpect(status().isOk());
    }

    @Test
    void disabledUserIsRejectedImmediately() throws Exception {
        OwnerJdbc.ownerAs(ws.tenantId()).update("update users set status = 'DISABLED'");
        principalCache.evict(ws.tenantId(), userId);
        me(session.accessToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void suspendedWorkspaceIsForbidden() throws Exception {
        OwnerJdbc.jdbc().update("update tenants set status = 'SUSPENDED' where id = ?", ws.tenantId());
        principalCache.evictTenant(ws.tenantId());
        me(session.accessToken()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Workspace suspended."));
    }

    @Test
    void publicAuthRoutesIgnoreStaleBearerTokens() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").header("Authorization", "Bearer expired.or.garbage")
                        .cookie(new Cookie("nexus_rt", session.refreshToken())))
                .andExpect(status().isOk());
    }
}
