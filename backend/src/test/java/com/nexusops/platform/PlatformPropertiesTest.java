package com.nexusops.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PlatformPropertiesTest {

    @Test
    void toStringNeverPrintsTheTotpKey() {
        String key = "c2VjcmV0LXRvdHAta2V5LXRoYXQtbXVzdC1uZXZlci1sZWFr";
        var properties = new PlatformProperties("nexusops-platform", Duration.ofMinutes(15), Duration.ofHours(8),
                "NexusOps", key);
        assertThat(properties.toString()).doesNotContain(key).contains("totpKey=****")
                .contains("audience=nexusops-platform").contains("totpIssuer=NexusOps");
        assertThat(properties.totpKey()).isEqualTo(key);
    }
}
