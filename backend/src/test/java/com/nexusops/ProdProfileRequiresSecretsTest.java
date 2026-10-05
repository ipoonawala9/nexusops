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

    @Test
    void prodProfileFailsFastWithoutAllowedOrigins() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(NexusOpsApplication.class)
                        .profiles("prod")
                        .run("--spring.main.web-application-type=none",
                                "--spring.datasource.password=x", "--spring.flyway.password=y"))
                .hasStackTraceContaining("Missing required secret")
                .hasStackTraceContaining("ALLOWED_ORIGINS");
    }
}
