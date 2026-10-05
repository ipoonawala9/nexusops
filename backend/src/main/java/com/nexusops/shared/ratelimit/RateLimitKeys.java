package com.nexusops.shared.ratelimit;

import com.nexusops.shared.cache.TenantKeys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** The only builder of rate-limit keys. Pre-auth keys cannot be tenant-prefixed: no tenant is known yet. */
public final class RateLimitKeys {

    private static final Pattern IP = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private RateLimitKeys() {}

    public static String ip(String rule, String ip) {
        String safe = ip != null && IP.matcher(ip).matches() ? ip : "unknown";
        return "rl:ip:" + safe + ":" + rule;
    }

    /** Hashed so arbitrary user input never becomes a raw key (and key length stays bounded). */
    public static String workspace(String rule, String rawWorkspace) {
        String normalized = rawWorkspace == null ? "" : rawWorkspace.strip().toLowerCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return "rl:ws:" + HexFormat.of().formatHex(digest) + ":" + rule;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String api(UUID tenantId, UUID userId) {
        return TenantKeys.key(tenantId, "user", userId.toString(), "rl", "api");
    }
}
