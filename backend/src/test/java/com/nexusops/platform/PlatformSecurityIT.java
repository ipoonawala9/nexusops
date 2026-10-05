package com.nexusops.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.platform.application.PlatformUserAdmin;
import com.nexusops.platform.domain.PlatformRole;
import com.nexusops.platform.domain.PlatformUserStatus;
import com.nexusops.platform.security.PlatformTokenService;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestPlatformUsers;
import com.nexusops.support.TestPlatformUsers.Operator;
import com.nexusops.support.TestTenants;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class PlatformSecurityIT extends IntegrationTestSupport {

    static final String STALE = "Your session is no longer valid. Please sign in again.";

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired PlatformUserAdmin admin;
    @Autowired PlatformTokenService platformTokens;
    @Autowired PlatformProperties properties;
    @Autowired RSAKey jwtRsaKey;

    Operator support;

    @BeforeEach
    void operator() {
        support = TestPlatformUsers.create(admin, PlatformRole.PLATFORM_SUPPORT);
    }

    private ResultActions platformMe(String token) throws Exception {
        return mvc.perform(get("/api/v1/platform/me").header("Authorization", "Bearer " + token));
    }

    @Test
    void aPlatformTokenReachesThePlatformApi() throws Exception {
        platformMe(platformTokens.issue(support.id(), 0).value()).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(support.email()))
                .andExpect(jsonPath("$.role").value("PLATFORM_SUPPORT"))
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.contains("platform.tenant.read")));
        mvc.perform(get("/api/v1/platform/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void tokensDoNotCrossBetweenTenantAndPlatformApis() throws Exception {
        var workspace = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("plat-x"));
        String tenantToken = TestTenants.login(mvc, workspace).accessToken();
        String platformToken = platformTokens.issue(support.id(), 0).value();

        platformMe(tenantToken).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tenantToken)).andExpect(status().isOk());
    }

    @Test
    void platformTokenWithATenantClaimIsRejected() throws Exception {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("nexusops")
                .audience(List.of(properties.audience()))
                .subject(support.id().toString())
                .issuedAt(now)
                .expiresAt(now.plus(5, ChronoUnit.MINUTES))
                .claim(PlatformTokenService.CLAIM_VERSION, 0)
                .claim("tid", UUID.randomUUID().toString())
                .build();
        String token = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwtRsaKey)))
                .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
        platformMe(token).andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenSignedWithAForeignKeyIsRejected() throws Exception {
        RSAKey foreign = new RSAKeyGenerator(2048).generate();
        var forger = new PlatformTokenService(new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(foreign))),
                properties, "nexusops", Clock.systemUTC());
        platformMe(forger.issue(support.id(), 0).value()).andExpect(status().isUnauthorized());
    }

    @Test
    void staleDisabledOrUnknownPrincipalsAreRejected() throws Exception {
        String token = platformTokens.issue(support.id(), 0).value();
        admin.resetPassword(support.email(), "a brand new platform passphrase");
        platformMe(token).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value(STALE));
        platformMe(platformTokens.issue(support.id(), 1).value()).andExpect(status().isOk());

        Operator other = TestPlatformUsers.create(admin, PlatformRole.PLATFORM_ADMIN);
        admin.setStatus(other.email(), PlatformUserStatus.DISABLED);
        platformMe(platformTokens.issue(other.id(), 1).value()).andExpect(status().isUnauthorized());
        platformMe(platformTokens.issue(UUID.randomUUID(), 0).value()).andExpect(status().isUnauthorized());
    }
}
