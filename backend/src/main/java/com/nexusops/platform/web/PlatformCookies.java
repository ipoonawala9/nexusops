package com.nexusops.platform.web;

import java.time.Duration;
import java.time.Instant;
import org.springframework.http.ResponseCookie;

/** The platform refresh cookie: httpOnly, Secure, SameSite=Strict, scoped to /api/v1/platform/auth (ADR-0007). */
final class PlatformCookies {

    static final String NAME = "nexus_prt";
    private static final String PATH = "/api/v1/platform/auth";

    private PlatformCookies() {}

    static ResponseCookie issue(String token, Instant expiresAt) {
        Duration maxAge = Duration.between(Instant.now(), expiresAt);
        return base(token).maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge).build();
    }

    static ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private static ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(true).sameSite("Strict").path(PATH);
    }
}
