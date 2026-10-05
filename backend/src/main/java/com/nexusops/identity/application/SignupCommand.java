package com.nexusops.identity.application;

public record SignupCommand(String workspaceName, String slug, String firstName, String lastName, String email,
        String password) {}
