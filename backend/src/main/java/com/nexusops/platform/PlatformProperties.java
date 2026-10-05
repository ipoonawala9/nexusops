package com.nexusops.platform;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code nexusops.platform.*}: platform token audience, lifetimes and the TOTP key/issuer (ADR-0007). */
@ConfigurationProperties("nexusops.platform")
public record PlatformProperties(
        String audience,
        Duration accessTokenTtl,
        Duration sessionMaxAge,
        String totpIssuer,
        String totpKey) {}
