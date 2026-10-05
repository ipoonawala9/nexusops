package com.nexusops.identity.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

final class AuthDtos {

    private AuthDtos() {}

    record SignupRequest(
            @NotBlank @Size(max = 120) String workspaceName,
            @NotBlank @Size(max = 64) String slug,
            @NotBlank @Size(max = 80) String firstName,
            @NotBlank @Size(max = 80) String lastName,
            @NotBlank @Size(max = 254) String email,
            @NotNull @Size(max = 128) String password) {}

    record SignupResponse(String slug, String status) {}

    record VerifyEmailRequest(@NotBlank @Size(max = 200) String token) {}

    record ResendVerificationRequest(@NotBlank @Size(max = 64) String workspace, @NotBlank @Size(max = 254) String email) {}

    record LoginRequest(
            @NotBlank @Size(max = 64) String workspace,
            @NotBlank @Size(max = 254) String email,
            @NotNull @Size(max = 128) String password) {}

    record TokenResponse(String accessToken, String tokenType, long expiresIn) {}
}
