package com.nexusops.identity.security;

import com.nexusops.shared.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Issues short-lived RS256 access tokens. Permissions are NOT embedded (resolved per request, ADR-0004). */
@Service
public class AccessTokenService {

    public static final String CLAIM_TENANT = "tid";
    public static final String CLAIM_TOKEN_VERSION = "tv";

    public record IssuedAccessToken(String value, Instant expiresAt) {}

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;

    @Autowired
    public AccessTokenService(JwtEncoder encoder, JwtProperties properties) {
        this(encoder, properties, Clock.systemUTC());
    }

    public AccessTokenService(JwtEncoder encoder, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedAccessToken issue(UUID tenantId, UUID userId, int tokenVersion) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(Ids.newId().toString())
                .claim(CLAIM_TENANT, tenantId.toString())
                .claim(CLAIM_TOKEN_VERSION, tokenVersion)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return new IssuedAccessToken(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), expiresAt);
    }
}
