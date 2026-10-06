package com.nexusops.shared.ratelimit;

import com.nexusops.shared.cache.TenantKeys;
import java.net.InetAddress;
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

    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");

    private RateLimitKeys() {}

    public static String ip(String rule, String ip) {
        return "rl:ip:" + normalizeIp(ip) + ":" + rule;
    }

    /** IPv4-mapped IPv6 becomes the IPv4 address; other IPv6 collapses to its /64 so a rotating suffix can't bypass limits. */
    static String normalizeIp(String ip) {
        if (ip == null || !IP.matcher(ip).matches()) {
            return "unknown";
        }
        InetAddress address;
        try {
            // Never resolves: only a strict dotted-quad or an input containing ':' (IPv6 literal) is parsed, and
            // InetAddress.ofLiteral rejects anything that is not a literal instead of falling back to DNS.
            if (ip.indexOf(':') >= 0 || IPV4.matcher(ip).matches()) {
                address = InetAddress.ofLiteral(ip);
            } else {
                return "unknown";
            }
        } catch (IllegalArgumentException e) {
            return "unknown";
        }
        byte[] b = address.getAddress();
        if (b.length == 4) { // plain IPv4, or IPv4-mapped IPv6 (the JDK already unwraps ::ffff:a.b.c.d)
            return address.getHostAddress();
        }
        return "%x:%x:%x:%x::/64".formatted(
                ((b[0] & 0xff) << 8) | (b[1] & 0xff), ((b[2] & 0xff) << 8) | (b[3] & 0xff),
                ((b[4] & 0xff) << 8) | (b[5] & 0xff), ((b[6] & 0xff) << 8) | (b[7] & 0xff));
    }

    /** Hashed so arbitrary user input never becomes a raw key (and key length stays bounded). */
    public static String workspace(String rule, String rawWorkspace) {
        return "rl:ws:" + sha256(normalize(rawWorkspace)) + ":" + rule;
    }

    /** Per-account login bucket: workspace + email, normalized and hashed together. */
    public static String account(String rawWorkspace, String rawEmail) {
        return "rl:acct:" + sha256(normalize(rawWorkspace) + "\n" + normalize(rawEmail)) + ":login";
    }

    /** Per-account platform login bucket, keyed by the canonical email (the platform has no workspace). */
    public static String platformAccount(String canonicalEmail) {
        return "rl:pacct:" + sha256(normalize(canonicalEmail)) + ":platform-login";
    }

    public static String api(UUID tenantId, UUID userId) {
        return TenantKeys.key(tenantId, "user", userId.toString(), "rl", "api");
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
