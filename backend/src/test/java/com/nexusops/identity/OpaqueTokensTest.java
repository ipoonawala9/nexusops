package com.nexusops.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.identity.application.OpaqueTokens;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OpaqueTokensTest {

    final UUID tenant = UUID.randomUUID();

    @Test
    void generatesTenantPrefixedHighEntropyTokens() {
        String token = OpaqueTokens.generate(tenant);
        assertThat(token).startsWith(tenant + ".");
        assertThat(token.substring(37)).matches("[A-Za-z0-9_-]{43}");
        assertThat(OpaqueTokens.generate(tenant)).isNotEqualTo(token);
    }

    @Test
    void hashIsStableLowercaseHexSha256() {
        String token = OpaqueTokens.generate(tenant);
        assertThat(OpaqueTokens.hash(token)).matches("[0-9a-f]{64}").isEqualTo(OpaqueTokens.hash(token));
    }

    @Test
    void parseRoundTripsTenantAndHash() {
        String token = OpaqueTokens.generate(tenant);
        var parsed = OpaqueTokens.parse(token).orElseThrow();
        assertThat(parsed.tenantId()).isEqualTo(tenant);
        assertThat(parsed.hash()).isEqualTo(OpaqueTokens.hash(token));
    }

    @Test
    void swappingTheTenantPrefixChangesTheHash() {
        String token = OpaqueTokens.generate(tenant);
        String tampered = UUID.randomUUID() + token.substring(36);
        assertThat(OpaqueTokens.parse(tampered).orElseThrow().hash()).isNotEqualTo(OpaqueTokens.hash(token));
    }

    @Test
    void rejectsGarbage() {
        assertThat(OpaqueTokens.parse(null)).isEmpty();
        assertThat(OpaqueTokens.parse("")).isEmpty();
        assertThat(OpaqueTokens.parse("no-dot")).isEmpty();
        assertThat(OpaqueTokens.parse("not-a-uuid.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")).isEmpty();
        assertThat(OpaqueTokens.parse(tenant + ".short")).isEmpty();
        assertThat(OpaqueTokens.parse(tenant + "." + "A".repeat(500))).isEmpty();
    }
}
