package com.nexusops.platform.web;

import com.nexusops.platform.application.PlatformAuthService;
import com.nexusops.platform.application.PlatformAuthService.Client;
import com.nexusops.platform.application.PlatformAuthService.PlatformSession;
import com.nexusops.shared.Emails;
import com.nexusops.shared.ratelimit.RateLimits;
import com.nexusops.shared.web.OriginGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public platform auth routes (listed in PublicEndpoints). */
@RestController
@RequestMapping("/api/v1/platform/auth")
class PlatformAuthController {

    record PlatformLoginRequest(
            @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String password,
            @NotBlank @Size(max = 16) String code) {}

    record PlatformTokenResponse(String accessToken, String tokenType, long expiresIn) {}

    private final PlatformAuthService auth;
    private final RateLimits rateLimits;
    private final OriginGuard originGuard;

    PlatformAuthController(PlatformAuthService auth, RateLimits rateLimits, OriginGuard originGuard) {
        this.auth = auth;
        this.rateLimits = rateLimits;
        this.originGuard = originGuard;
    }

    @PostMapping("/login")
    ResponseEntity<PlatformTokenResponse> login(@Valid @RequestBody PlatformLoginRequest request, HttpServletRequest http) {
        rateLimits.checkPlatformLogin(http.getRemoteAddr(), Emails.tryNormalize(request.email()).orElse(null));
        return tokens(auth.login(request.email(), request.password(), request.code(), client(http)));
    }

    @PostMapping("/refresh")
    ResponseEntity<PlatformTokenResponse> refresh(
            @CookieValue(name = PlatformCookies.NAME, required = false) String token,
            @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin,
            HttpServletRequest http) {
        originGuard.check(origin);
        rateLimits.checkPublic("refresh", http.getRemoteAddr());
        return tokens(auth.refresh(token, client(http)));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(
            @CookieValue(name = PlatformCookies.NAME, required = false) String token,
            @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin) {
        originGuard.check(origin);
        if (token != null) {
            auth.logout(token);
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, PlatformCookies.clear().toString()).build();
    }

    private static ResponseEntity<PlatformTokenResponse> tokens(PlatformSession session) {
        long expiresIn = Duration.between(Instant.now(), session.accessTokenExpiresAt()).toSeconds();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        PlatformCookies.issue(session.refreshToken(), session.refreshTokenExpiresAt()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new PlatformTokenResponse(session.accessToken(), "Bearer", Math.round(expiresIn / 60.0) * 60));
    }

    private static Client client(HttpServletRequest http) {
        return new Client(http.getRemoteAddr(), http.getHeader(HttpHeaders.USER_AGENT));
    }
}
