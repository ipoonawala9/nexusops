package com.nexusops.platform.totp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class TotpTest {

    /** RFC 6238 Appendix B test secret (SHA-1). */
    static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesTheRfc6238TestVectorsTruncatedToSixDigits() {
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(59)))).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1111111109)))).isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1111111111)))).isEqualTo("050471");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1234567890)))).isEqualTo("005924");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(2000000000)))).isEqualTo("279037");
    }

    @Test
    void codesAreAsciiDigitsWhateverTheDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-SA"));
            assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(59)))).isEqualTo("287082");
            assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1234567890)))).isEqualTo("005924")
                    .matches("[0-9]{6}");
            Instant now = Instant.ofEpochSecond(1_800_000_000L);
            String code = Totp.code(RFC_SECRET, Totp.step(now));
            assertThat(Totp.verify(RFC_SECRET, code, now, 0)).hasValue(Totp.step(now));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void acceptsOneStepOfSkewEitherWay() {
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        long step = Totp.step(now);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 1), now, 0)).hasValue(step - 1);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step), now, 0)).hasValue(step);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step + 1), now, 0)).hasValue(step + 1);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 2), now, 0)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step + 2), now, 0)).isEmpty();
    }

    @Test
    void rejectsAStepAtOrBeforeTheLastUsedOne() {
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        long step = Totp.step(now);
        String code = Totp.code(RFC_SECRET, step);
        assertThat(Totp.verify(RFC_SECRET, code, now, step)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, code, now, step - 1)).hasValue(step);
    }

    @Test
    void rejectsMalformedInputAndToleratesSpaces() {
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        String code = Totp.code(RFC_SECRET, Totp.step(now));
        assertThat(Totp.verify(RFC_SECRET, null, now, 0)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, "12345", now, 0)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, "abcdef", now, 0)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, code.substring(0, 3) + " " + code.substring(3), now, 0)).isPresent();
    }

    @Test
    void encodesBase32AndBuildsAnOtpauthUri() {
        assertThat(Totp.base32(RFC_SECRET)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(Totp.otpauthUri("NexusOps", "ops@nexusops.test", RFC_SECRET)).isEqualTo(
                "otpauth://totp/NexusOps:ops%40nexusops.test?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
                        + "&issuer=NexusOps&algorithm=SHA1&digits=6&period=30");
        assertThat(Totp.newSecret()).hasSize(20).isNotEqualTo(Totp.newSecret());
    }
}
