package com.nexusops.identity.web;

import com.nexusops.identity.application.AuthResult;
import com.nexusops.identity.application.ClientInfo;
import com.nexusops.identity.application.Emails;
import com.nexusops.identity.application.LoginService;
import com.nexusops.identity.application.RefreshService;
import com.nexusops.identity.application.SignupCommand;
import com.nexusops.identity.application.SignupService;
import com.nexusops.identity.web.AuthDtos.LoginRequest;
import com.nexusops.identity.web.AuthDtos.ResendVerificationRequest;
import com.nexusops.identity.web.AuthDtos.SignupRequest;
import com.nexusops.identity.web.AuthDtos.SignupResponse;
import com.nexusops.identity.web.AuthDtos.TokenResponse;
import com.nexusops.identity.web.AuthDtos.VerifyEmailRequest;
import com.nexusops.shared.ratelimit.RateLimits;
import com.nexusops.tenancy.Slug;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication routes. All are public (listed in PublicEndpoints) except {@code POST /logout-all},
 * which requires an authenticated caller.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final SignupService signup;
    private final LoginService login;
    private final RefreshService refresh;
    private final OriginGuard originGuard;
    private final RateLimits rateLimits;

    AuthController(SignupService signup, LoginService login, RefreshService refresh, OriginGuard originGuard,
            RateLimits rateLimits) {
        this.signup = signup;
        this.login = login;
        this.refresh = refresh;
        this.originGuard = originGuard;
        this.rateLimits = rateLimits;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    SignupResponse signup(@Valid @RequestBody SignupRequest request, HttpServletRequest http) {
        rateLimits.checkPublic("signup", http.getRemoteAddr());
        var result = signup.signup(new SignupCommand(request.workspaceName(), request.slug(), request.firstName(),
                request.lastName(), request.email(), request.password()));
        return new SignupResponse(result.slug(), "PENDING_VERIFICATION");
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void verifyEmail(@Valid @RequestBody VerifyEmailRequest request, HttpServletRequest http) {
        rateLimits.checkPublic("verify-email", http.getRemoteAddr());
        signup.verifyEmail(request.token());
    }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void resendVerification(@Valid @RequestBody ResendVerificationRequest request, HttpServletRequest http) {
        rateLimits.checkPublic("resend-verification", http.getRemoteAddr());
        signup.resendVerification(request.workspace(), request.email());
    }

    @PostMapping("/login")
    ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        // Rate-limit keys use the same canonical workspace/email the login lookup uses, so padded variants share a bucket.
        String workspace = Slug.tryNormalize(request.workspace()).orElse(null);
        String email = Emails.tryNormalize(request.email()).orElse(null);
        rateLimits.checkLogin(http.getRemoteAddr(), workspace, email);
        return tokens(login.login(request.workspace(), request.email(), request.password(), client(http)));
    }

    @PostMapping("/refresh")
    ResponseEntity<TokenResponse> refresh(
            @CookieValue(name = RefreshCookies.NAME, required = false) String token,
            @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin,
            HttpServletRequest http) {
        originGuard.check(origin);
        rateLimits.checkPublic("refresh", http.getRemoteAddr());
        return tokens(refresh.refresh(token, client(http)));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(
            @CookieValue(name = RefreshCookies.NAME, required = false) String token,
            @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin) {
        originGuard.check(origin);
        if (token != null) {
            refresh.logout(token);
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, RefreshCookies.clear().toString()).build();
    }

    @PostMapping("/logout-all")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<Void> logoutAll() {
        refresh.logoutAll();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, RefreshCookies.clear().toString()).build();
    }

    private static ResponseEntity<TokenResponse> tokens(AuthResult result) {
        long expiresIn = Duration.between(Instant.now(), result.accessTokenExpiresAt()).toSeconds();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        RefreshCookies.issue(result.refreshToken(), result.refreshTokenExpiresAt()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new TokenResponse(result.accessToken(), "Bearer", Math.round(expiresIn / 60.0) * 60));
    }

    private static ClientInfo client(HttpServletRequest http) {
        return new ClientInfo(http.getRemoteAddr(), http.getHeader(HttpHeaders.USER_AGENT));
    }
}
