package com.nexusops.shared.security;

import com.nexusops.shared.web.ApiProblem;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Length 12–128, not a known-common password, not the email address (spec §6). */
@Component
public class PasswordPolicy {

    private final Set<String> common;

    public PasswordPolicy() {
        this.common = loadCommonPasswords();
    }

    public void check(String password, String email) {
        if (password == null || password.length() < 12) {
            throw invalid("Use at least 12 characters.");
        }
        if (password.length() > 128) {
            throw invalid("Use at most 128 characters.");
        }
        if (common.contains(password.toLowerCase(Locale.ROOT))) {
            throw invalid("This password is too common. Choose another.");
        }
        if (email != null && password.equalsIgnoreCase(email.strip())) {
            throw invalid("Don't use your email address as your password.");
        }
    }

    private static ApiProblem invalid(String message) {
        return ApiProblem.badRequestField("password", message);
    }

    private static Set<String> loadCommonPasswords() {
        var resource = new ClassPathResource("security/common-passwords.txt");
        try (var reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .map(l -> l.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load common password list", e);
        }
    }
}
