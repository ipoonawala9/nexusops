package com.nexusops.directory.domain;

import com.nexusops.shared.Emails;
import com.nexusops.shared.web.ApiProblem;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Normalizes optional contact fields. Blank becomes null; invalid input is a 400 field error. */
public final class ContactDetails {

    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9 ().\\-/]{3,40}$");
    private static final Pattern DOMAIN = Pattern.compile(
            "^(?=.{3,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{0,61}[a-z0-9]$");
    private static final Pattern SCHEME = Pattern.compile("^[a-z][a-z0-9+.-]*://");

    private ContactDetails() {}

    public static String email(String raw) {
        return raw == null || raw.isBlank() ? null : Emails.normalize(raw);
    }

    public static String phone(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip().replaceAll("\\s+", " ");
        long digits = value.chars().filter(c -> c >= '0' && c <= '9').count();
        if (!PHONE.matcher(value).matches() || digits < 3) {
            throw ApiProblem.badRequestField("phone", "Enter a phone number using digits, spaces and + ( ) - . /");
        }
        return value;
    }

    /** "https://WWW.Acme.com/about" becomes "acme.com". */
    public static String domain(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = SCHEME.matcher(raw.strip().toLowerCase(Locale.ROOT)).replaceFirst("");
        for (char stop : new char[] {'/', '?', '#'}) {
            int index = value.indexOf(stop);
            if (index >= 0) {
                value = value.substring(0, index);
            }
        }
        value = value.substring(value.lastIndexOf('@') + 1);
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(0, colon);
        }
        if (value.startsWith("www.")) {
            value = value.substring(4);
        }
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        if (!DOMAIN.matcher(value).matches()) {
            throw ApiProblem.badRequestField("domain", "Enter a domain like example.com.");
        }
        return value;
    }

    /** An http(s) address; "acme.com" becomes "https://acme.com". */
    public static String website(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip();
        if (!SCHEME.matcher(value.toLowerCase(Locale.ROOT)).find()) {
            value = "https://" + value;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null && value.length() <= 255) {
                return value;
            }
        } catch (URISyntaxException invalid) {
            // falls through to the field error
        }
        throw ApiProblem.badRequestField("website", "Enter a web address like https://example.com.");
    }
}
