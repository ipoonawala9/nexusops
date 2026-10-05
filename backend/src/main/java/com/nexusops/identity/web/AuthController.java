package com.nexusops.identity.web;

import com.nexusops.identity.application.SignupCommand;
import com.nexusops.identity.application.SignupService;
import com.nexusops.identity.web.AuthDtos.ResendVerificationRequest;
import com.nexusops.identity.web.AuthDtos.SignupRequest;
import com.nexusops.identity.web.AuthDtos.SignupResponse;
import com.nexusops.identity.web.AuthDtos.VerifyEmailRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Public authentication routes — each is listed in PublicEndpoints. */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final SignupService signup;

    AuthController(SignupService signup) {
        this.signup = signup;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    SignupResponse signup(@Valid @RequestBody SignupRequest request) {
        var result = signup.signup(new SignupCommand(request.workspaceName(), request.slug(), request.firstName(),
                request.lastName(), request.email(), request.password()));
        return new SignupResponse(result.slug(), "PENDING_VERIFICATION");
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        signup.verifyEmail(request.token());
    }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        signup.resendVerification(request.workspace(), request.email());
    }
}
