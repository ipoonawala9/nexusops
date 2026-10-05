package com.nexusops.platform.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import java.util.List;
import java.util.Objects;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** The platform chain's decoder: RS256 only, platform audience, a {@code pv} claim and NO tenant claim. */
final class PlatformJwt {

    private PlatformJwt() {}

    static JwtDecoder decoder(RSAKey key, String issuer, String audience) {
        NimbusJwtDecoder decoder;
        try {
            decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot build platform JWT decoder", e);
        }
        OAuth2TokenValidator<Jwt> noTenant = jwt -> jwt.hasClaim("tid")
                ? OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Not a platform token", null))
                : OAuth2TokenValidatorResult.success();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<Object>("exp", Objects::nonNull),
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(audience)),
                new JwtClaimValidator<Object>(PlatformTokenService.CLAIM_VERSION, pv -> pv instanceof Number),
                new JwtClaimValidator<Object>("sub", sub -> sub instanceof String s && !s.isBlank()),
                noTenant));
        return decoder;
    }
}
