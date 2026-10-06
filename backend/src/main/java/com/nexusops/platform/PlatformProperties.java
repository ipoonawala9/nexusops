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
        String totpKey) {

    /** Never prints {@code totpKey} (PLATFORM_TOTP_KEY), e.g. in logs or failure analysis. */
    @Override
    public String toString() {
        return "PlatformProperties[audience=" + audience + ", accessTokenTtl=" + accessTokenTtl
                + ", sessionMaxAge=" + sessionMaxAge + ", totpIssuer=" + totpIssuer + ", totpKey=****]";
    }
}
