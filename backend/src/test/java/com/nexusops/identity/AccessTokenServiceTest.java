package com.nexusops.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.identity.security.AccessTokenService;
import com.nexusops.identity.security.JwtKeyConfig;
import com.nexusops.identity.security.JwtProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

class AccessTokenServiceTest {

    static final JwtProperties PROPS = new JwtProperties("nexusops", "nexusops-tenant",
            Duration.ofMinutes(15), Duration.ofDays(14), "", "", true);

    final RSAKey key = JwtKeyConfig.rsaKey(PROPS);
    final JwtEncoder encoder = JwtKeyConfig.encoder(key);
    final JwtDecoder decoder = JwtKeyConfig.decoder(key, PROPS);
    final UUID tenant = UUID.randomUUID();
    final UUID user = UUID.randomUUID();

    private AccessTokenService serviceAt(Instant now) {
        return new AccessTokenService(encoder, PROPS, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void issuesAVerifiableTokenWithTenantUserAndVersion() {
        var issued = serviceAt(Instant.now()).issue(tenant, user, 3);
        var jwt = decoder.decode(issued.value());
        assertThat(jwt.getSubject()).isEqualTo(user.toString());
        assertThat(jwt.getClaimAsString("tid")).isEqualTo(tenant.toString());
        assertThat(((Number) jwt.getClaim("tv")).intValue()).isEqualTo(3);
        assertThat(jwt.getAudience()).containsExactly("nexusops-tenant");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(issued.expiresAt()).isAfter(Instant.now().plus(Duration.ofMinutes(14)));
    }

    @Test
    void rejectsExpiredTokens() {
        var issued = serviceAt(Instant.now().minus(Duration.ofHours(1))).issue(tenant, user, 0);
        assertThatThrownBy(() -> decoder.decode(issued.value())).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTokensSignedWithAnotherKey() {
        RSAKey otherKey = JwtKeyConfig.rsaKey(PROPS);
        String forged = new AccessTokenService(JwtKeyConfig.encoder(otherKey), PROPS, Clock.systemUTC())
                .issue(tenant, user, 0).value();
        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsUnsignedAlgNoneTokens() {
        String header = b64("{\"alg\":\"none\"}");
        String payload = b64("{\"sub\":\"" + user + "\",\"tid\":\"" + tenant + "\",\"tv\":0,\"iss\":\"nexusops\","
                + "\"aud\":\"nexusops-tenant\",\"exp\":" + (Instant.now().getEpochSecond() + 600) + "}");
        assertThatThrownBy(() -> decoder.decode(header + "." + payload + ".")).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsHmacKeyConfusionUsingThePublicKeyAsSecret() throws Exception {
        byte[] secret = key.toRSAPublicKey().getEncoded();
        var claims = new JWTClaimsSet.Builder().subject(user.toString()).claim("tid", tenant.toString()).claim("tv", 0)
                .issuer("nexusops").audience("nexusops-tenant").expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret));
        assertThatThrownBy(() -> decoder.decode(jwt.serialize())).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsWrongAudienceIssuerOrMissingTenant() {
        assertThatThrownBy(() -> decoder.decode(sign(b -> b.audience(List.of("nexusops-platform")))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(sign(b -> b.issuer("someone-else"))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(sign(b -> b.claims(c -> c.remove("tid")))))
                .isInstanceOf(JwtException.class);
    }

    private String sign(java.util.function.Consumer<JwtClaimsSet.Builder> tweak) {
        var builder = JwtClaimsSet.builder().subject(user.toString()).issuer("nexusops")
                .audience(List.of("nexusops-tenant")).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("tid", tenant.toString()).claim("tv", 0);
        tweak.accept(builder);
        var header = org.springframework.security.oauth2.jwt.JwsHeader
                .with(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, builder.build())).getTokenValue();
    }

    @Test
    void failsFastWithoutKeysWhenEphemeralKeysAreDisabled() {
        var prod = new JwtProperties("nexusops", "nexusops-tenant", Duration.ofMinutes(15), Duration.ofDays(14), "", "", false);
        assertThatThrownBy(() -> JwtKeyConfig.rsaKey(prod))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PRIVATE_KEY");
    }

    @Test
    void loadsPemKeysWhenConfigured() throws Exception {
        String privatePem = pem("PRIVATE KEY", key.toRSAPrivateKey().getEncoded());
        String publicPem = pem("PUBLIC KEY", key.toRSAPublicKey().getEncoded());
        var configured = new JwtProperties("nexusops", "nexusops-tenant", Duration.ofMinutes(15), Duration.ofDays(14),
                privatePem, publicPem, false);
        assertThat(JwtKeyConfig.rsaKey(configured).toRSAPublicKey()).isEqualTo(key.toRSAPublicKey());
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END " + type + "-----\n";
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes());
    }
}
