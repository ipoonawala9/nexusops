package com.nexusops.collaboration;

import java.util.Locale;
import java.util.regex.Pattern;

/** Makes client-supplied file names and content types safe to store and to send back in headers. */
public final class FileNames {

    private static final int MAX = 255;
    private static final Pattern UNSAFE = Pattern.compile("[\\p{Cntrl}\\p{Zl}\\p{Zp}\"]");
    private static final Pattern CONTENT_TYPE = Pattern.compile(
            "^[a-z0-9][a-z0-9!#$&^_.+-]{0,62}/[a-z0-9][a-z0-9!#$&^_.+-]{0,62}$");

    private FileNames() {}

    public static String sanitize(String raw) {
        String name = raw == null ? "" : raw;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = UNSAFE.matcher(name).replaceAll("").strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return "file";
        }
        if (name.length() > MAX) {
            int dot = name.lastIndexOf('.');
            String extension = dot > 0 && name.length() - dot <= 16 ? name.substring(dot) : "";
            name = name.substring(0, MAX - extension.length()) + extension;
        }
        return name;
    }

    public static String contentType(String raw) {
        if (raw == null) {
            return "application/octet-stream";
        }
        String base = raw.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return CONTENT_TYPE.matcher(base).matches() ? base : "application/octet-stream";
    }
}
