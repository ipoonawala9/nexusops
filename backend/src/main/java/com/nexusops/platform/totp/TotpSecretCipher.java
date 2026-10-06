package com.nexusops.platform.totp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts TOTP secrets at rest (spec §5 {@code totp_secret_enc}): AES-256-GCM with a random 96-bit IV and the
 * owning platform user's id as associated data, so a ciphertext copied onto another row fails to decrypt.
 * Stored form: {@code v1:} + base64(iv ‖ ciphertext ‖ tag).
 */
public final class TotpSecretCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public TotpSecretCipher(String base64Key) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key == null ? "" : base64Key.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("PLATFORM_TOTP_KEY must be 32 bytes, base64-encoded.");
        }
        if (raw.length != 32) {
            throw new IllegalStateException("PLATFORM_TOTP_KEY must be 32 bytes, base64-encoded.");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(byte[] secret, UUID owner) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(owner.toString().getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(secret);
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + sealed.length)
                    .put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot encrypt TOTP secret", e);
        }
    }

    public byte[] decrypt(String stored, UUID owner) {
        try {
            if (stored == null || !stored.startsWith(PREFIX)) {
                throw new IllegalStateException("Unknown TOTP secret format");
            }
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            cipher.updateAAD(owner.toString().getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot decrypt TOTP secret", e);
        }
    }
}
