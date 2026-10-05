package com.nexusops.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Opaque bearer secrets of the form {tenantId}.{256-bit random}. The tenant prefix lets the server
 * bind RLS context before lookup; only the SHA-256 of the whole token is stored, so tampering with
 * the prefix simply finds nothing (spec §5).
 */
public final class OpaqueTokens {

    public record Parsed(UUID tenantId, String hash) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern RANDOM_PART = Pattern.compile("[A-Za-z0-9_-]{43}");

    private OpaqueTokens() {}

    public static String generate(UUID tenantId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return tenantId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Optional<Parsed> parse(String token) {
        if (token == null || token.length() != 36 + 1 + 43) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot != 36 || !RANDOM_PART.matcher(token.substring(dot + 1)).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Parsed(UUID.fromString(token.substring(0, dot)), hash(token)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
