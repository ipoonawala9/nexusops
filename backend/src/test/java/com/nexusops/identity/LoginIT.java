package com.nexusops.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class LoginIT extends IntegrationTestSupport {

    static final String GENERIC = "Invalid workspace, email or password.";

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired JwtDecoder jwtDecoder;

    private ResultActions login(String workspace, String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"workspace":"%s","email":"%s","password":"%s"}""".formatted(workspace, email, password)));
    }

    @Test
    void successfulLoginReturnsAccessTokenAndHardenedRefreshCookie() throws Exception {
        Workspace ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("login"));
        var result = login(ws.slug(), ws.email(), ws.password())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andReturn();

        String setCookie = String.join("\n", result.getResponse().getHeaders("Set-Cookie"));
        assertThat(setCookie).contains("nexus_rt=").contains("HttpOnly").contains("Secure")
                .contains("SameSite=Strict").contains("Path=/api/v1/auth").contains("Max-Age=");

        var jwt = jwtDecoder.decode(TestTenants.accessTokenOf(result));
        assertThat(jwt.getClaimAsString("tid")).isEqualTo(ws.tenantId().toString());

        var owner = OwnerJdbc.ownerAs(ws.tenantId());
        assertThat(owner.queryForObject("select last_login_at is not null from users", Boolean.class)).isTrue();
        assertThat(owner.queryForList("select action from audit_events", String.class)).contains("LoginSucceeded");
    }

    @Test
    void workspaceAndEmailAreCaseInsensitive() throws Exception {
        Workspace ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("case"));
        login(" " + ws.slug().toUpperCase(), ws.email().toUpperCase(), ws.password()).andExpect(status().isOk());
    }

    @Test
    void everyCredentialFailureIsTheSameGeneric401AndIsAudited() throws Exception {
        Workspace ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("fail"));
        login(ws.slug(), ws.email(), "wrong password!!").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(GENERIC));
        login(ws.slug(), "nobody@x.test", ws.password()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(GENERIC));
        login("no-such-workspace", ws.email(), ws.password()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(GENERIC));

        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = 'LoginFailed'", Long.class)).isEqualTo(2);
        assertThat(OwnerJdbc.superuser().queryForObject(
                "select count(*) from audit_events where action = 'LoginFailed' and tenant_id is null "
                        + "and metadata->>'workspace' = 'no-such-workspace'", Long.class)).isPositive();
    }

    @Test
    void unverifiedEmailIsRevealedOnlyAfterTheCorrectPassword() throws Exception {
        Workspace ws = TestTenants.signup(mvc, TestTenants.uniqueSlug("unver"));
        login(ws.slug(), ws.email(), "wrong password!!").andExpect(status().isUnauthorized());
        login(ws.slug(), ws.email(), ws.password()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Email address not verified."));
    }

    @Test
    void suspendedWorkspaceCannotLogIn() throws Exception {
        Workspace ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("susp"));
        OwnerJdbc.jdbc().update("update tenants set status = 'SUSPENDED' where id = ?", ws.tenantId());
        login(ws.slug(), ws.email(), ws.password()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Workspace suspended."));
    }

    @Test
    void workspaceThatIsNotActiveCannotLogIn() throws Exception {
        Workspace ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("pend"));
        OwnerJdbc.jdbc().update("update tenants set status = 'PENDING_VERIFICATION' where id = ?", ws.tenantId());
        login(ws.slug(), ws.email(), ws.password()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Workspace is not active."));
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForList(
                "select metadata->>'reason' from audit_events where action = 'LoginFailed'", String.class))
                .containsExactly("WORKSPACE_PENDING_VERIFICATION");
    }
}
