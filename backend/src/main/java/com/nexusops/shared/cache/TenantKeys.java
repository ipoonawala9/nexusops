package com.nexusops.shared.cache;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** The only way to build Redis keys: every key is prefixed with its tenant (spec §4.4). */
public final class TenantKeys {

    private static final Pattern SAFE_PART = Pattern.compile("[A-Za-z0-9._-]+");

    private TenantKeys() {}

    public static String key(UUID tenantId, String... parts) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (parts.length == 0) {
            throw new IllegalArgumentException("At least one key part is required");
        }
        for (String part : parts) {
            if (part == null || !SAFE_PART.matcher(part).matches()) {
                throw new IllegalArgumentException("Unsafe cache key part: " + part);
            }
        }
        return "tenant:" + tenantId + ":" + String.join(":", parts);
    }

    public static String tenantPattern(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        return "tenant:" + tenantId + ":*";
    }
}
