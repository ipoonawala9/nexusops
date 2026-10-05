package com.nexusops.platform.security;

import com.nexusops.platform.PlatformProperties;
import com.nexusops.shared.Ids;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/**
 * Platform access tokens (ADR-0007): RS256, aud=nexusops-platform, {@code pv} = the platform user's token version,
 * no {@code tid}. The encoder is deliberately not a bean, so identity's JwtEncoder stays the only one.
 */
@Service
public class PlatformTokenService {

    public static final String CLAIM_VERSION = "pv";

    public record IssuedToken(String value, Instant expiresAt) {}

    private final JwtEncoder encoder;
    private final PlatformProperties properties;
    private final String issuer;
    private final Clock clock;

    @Autowired
    PlatformTokenService(RSAKey jwtRsaKey, PlatformProperties properties,
            @Value("${nexusops.security.jwt.issuer}") String issuer) {
        this(new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwtRsaKey))), properties, issuer, Clock.systemUTC());
    }

    public PlatformTokenService(JwtEncoder encoder, PlatformProperties properties, String issuer, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.issuer = issuer;
        this.clock = clock;
    }

    public IssuedToken issue(UUID platformUserId, int tokenVersion) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(properties.audience()))
                .subject(platformUserId.toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(Ids.newId().toString())
                .claim(CLAIM_VERSION, tokenVersion)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return new IssuedToken(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), expiresAt);
    }
}
