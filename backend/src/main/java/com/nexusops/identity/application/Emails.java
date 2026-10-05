package com.nexusops.identity.application;

import com.nexusops.shared.web.ApiProblem;
import java.util.Locale;
import java.util.regex.Pattern;

public final class Emails {

    private static final Pattern SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private Emails() {}

    public static String normalize(String raw) {
        String email = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (email.length() < 3 || email.length() > 254 || !SHAPE.matcher(email).matches()) {
            throw ApiProblem.badRequestField("email", "Enter a valid email address.");
        }
        return email;
    }
}
