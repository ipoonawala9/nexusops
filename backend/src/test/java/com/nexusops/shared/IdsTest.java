package com.nexusops.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void generatesVersion7RfcVariantIds() {
        UUID id = Ids.newId();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void idsAreUniqueAndTimeOrderedAcrossMilliseconds() throws InterruptedException {
        UUID first = Ids.newId();
        Thread.sleep(2);
        UUID second = Ids.newId();
        assertThat(first.getMostSignificantBits() >>> 16).isLessThan(second.getMostSignificantBits() >>> 16);
        var seen = new HashSet<UUID>();
        for (int i = 0; i < 10_000; i++) {
            assertThat(seen.add(Ids.newId())).isTrue();
        }
    }
}
