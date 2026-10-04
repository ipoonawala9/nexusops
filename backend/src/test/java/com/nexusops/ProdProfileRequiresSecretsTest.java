package com.nexusops;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

class ProdProfileRequiresSecretsTest {

    @Test
    void prodProfileFailsFastWithoutDatabasePassword() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(NexusOpsApplication.class)
                        .profiles("prod")
                        .properties("spring.main.web-application-type=none")
                        .run())
                .hasStackTraceContaining("Missing required secret")
                .hasStackTraceContaining("DB_APP_PASSWORD");
    }
}
