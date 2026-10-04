package com.nexusops;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Starts the real application as the schema owner (with lazy init on) and expects it to refuse. */
class DatabaseRoleGuardStartupIT {

    @Test
    void refusesToStartAsSchemaOwnerEvenWithLazyInitialization() {
        var postgres = IntegrationTestSupport.POSTGRES;
        var redis = IntegrationTestSupport.REDIS;
        assertThatThrownBy(() -> new SpringApplicationBuilder(NexusOpsApplication.class)
                        .profiles("test")
                        // command-line args: highest precedence, like real deployment overrides
                        .run(
                                "--spring.main.web-application-type=none",
                                "--spring.main.lazy-initialization=true",
                                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                                "--spring.datasource.username=nexusops_owner",
                                "--spring.datasource.password=" + IntegrationTestSupport.OWNER_PASSWORD,
                                "--spring.flyway.user=nexusops_owner",
                                "--spring.flyway.password=" + IntegrationTestSupport.OWNER_PASSWORD,
                                "--spring.data.redis.host=" + redis.getHost(),
                                "--spring.data.redis.port=" + redis.getMappedPort(6379))
                        .close())
                .hasStackTraceContaining("Refusing to start")
                .hasStackTraceContaining("nexusops_owner");
    }
}
