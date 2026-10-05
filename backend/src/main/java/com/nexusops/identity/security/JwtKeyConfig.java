package com.nexusops.identity.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** RS256 key material (ADR-0003). Production keys come from JWT_PRIVATE_KEY / JWT_PUBLIC_KEY (PEM). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);

    @Bean
    RSAKey jwtRsaKey(JwtProperties properties) {
        return rsaKey(properties);
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey jwtRsaKey) {
        return encoder(jwtRsaKey);
    }

    @Bean
    JwtDecoder jwtDecoder(RSAKey jwtRsaKey, JwtProperties properties) {
        return decoder(jwtRsaKey, properties);
    }

    public static RSAKey rsaKey(JwtProperties properties) {
        try {
            if (notBlank(properties.privateKey()) && notBlank(properties.publicKey())) {
                KeyFactory rsa = KeyFactory.getInstance("RSA");
                var publicKey = (RSAPublicKey) rsa.generatePublic(new X509EncodedKeySpec(pemBody(properties.publicKey())));
                var privateKey = (RSAPrivateKey) rsa.generatePrivate(new PKCS8EncodedKeySpec(pemBody(properties.privateKey())));
                return new RSAKey.Builder(publicKey).privateKey(privateKey).keyIDFromThumbprint().build();
            }
            if (properties.ephemeralKeys()) {
                log.warn("Using an ephemeral JWT signing key (local/test only); tokens are invalid after restart.");
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                var pair = generator.generateKeyPair();
                return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                        .privateKey((RSAPrivateKey) pair.getPrivate()).keyIDFromThumbprint().build();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JWT key material", e);
        }
        throw new IllegalStateException(
                "Missing required secret: set environment variables JWT_PRIVATE_KEY and JWT_PUBLIC_KEY (PEM).");
    }

    public static JwtEncoder encoder(RSAKey key) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    public static JwtDecoder decoder(RSAKey key, JwtProperties properties) {
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey())
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build();
            OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(properties.issuer()),
                    new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(properties.audience())),
                    new JwtClaimValidator<Object>("tid", tid -> tid instanceof String s && !s.isBlank()),
                    new JwtClaimValidator<Object>("tv", tv -> tv instanceof Number),
                    new JwtClaimValidator<Object>("sub", sub -> sub instanceof String s && !s.isBlank()));
            decoder.setJwtValidator(validator);
            return decoder;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot build JWT decoder", e);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static byte[] pemBody(String pem) {
        String body = pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
