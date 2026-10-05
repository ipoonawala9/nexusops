package com.nexusops.identity.application;

import com.nexusops.shared.web.ApiProblem;

final class Names {

    private Names() {}

    static String require(String raw, String field) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > 80) {
            throw ApiProblem.badRequestField(field, "Enter between 1 and 80 characters.");
        }
        return name;
    }
}
