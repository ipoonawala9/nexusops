package com.nexusops.platform.totp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TotpSecretCipherTest {

    static final String KEY = Base64.getEncoder().encodeToString("test-only-totp-key-32-bytes!!!!!".getBytes());
    final TotpSecretCipher cipher = new TotpSecretCipher(KEY);

    @Test
    void roundTripsAndUsesAFreshIvEachTime() {
        UUID owner = UUID.randomUUID();
        byte[] secret = Totp.newSecret();
        String first = cipher.encrypt(secret, owner);
        assertThat(first).startsWith("v1:").doesNotContain(Totp.base32(secret));
        assertThat(cipher.encrypt(secret, owner)).isNotEqualTo(first);
        assertThat(cipher.decrypt(first, owner)).isEqualTo(secret);
    }

    @Test
    void aCiphertextIsBoundToItsOwner() {
        String stored = cipher.encrypt(Totp.newSecret(), UUID.randomUUID());
        assertThatThrownBy(() -> cipher.decrypt(stored, UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperingAndWrongKeysAreDetected() {
        UUID owner = UUID.randomUUID();
        String stored = cipher.encrypt(Totp.newSecret(), owner);
        char last = stored.charAt(stored.length() - 2);
        String tampered = stored.substring(0, stored.length() - 2) + (last == 'A' ? 'B' : 'A') + stored.charAt(stored.length() - 1);
        assertThatThrownBy(() -> cipher.decrypt(tampered, owner)).isInstanceOf(IllegalStateException.class);
        var other = new TotpSecretCipher(Base64.getEncoder().encodeToString("another-test-key-of-32-bytes!!!!".getBytes()));
        assertThatThrownBy(() -> other.decrypt(stored, owner)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAKeyThatIsNotThirtyTwoBytes() {
        assertThatThrownBy(() -> new TotpSecretCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PLATFORM_TOTP_KEY");
        assertThatThrownBy(() -> new TotpSecretCipher("not base64 !!"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PLATFORM_TOTP_KEY");
    }
}
