package com.nexusops.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.NexusOpsApplication;
import com.nexusops.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

class PlatformCliStartupIT {

    @Test
    void theCliStartsWithoutAWebServerAndExitsWithTheCommandsCode() {
        var pg = IntegrationTestSupport.POSTGRES; // starts the shared containers
        var context = new SpringApplicationBuilder(NexusOpsApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                // command-line args: highest precedence (builder .properties() are defaults, which application.yml overrides)
                .run(
                        "--spring.datasource.url=" + pg.getJdbcUrl(),
                        "--spring.datasource.username=nexusops_app",
                        "--spring.datasource.password=" + IntegrationTestSupport.APP_PASSWORD,
                        "--spring.flyway.user=nexusops_owner",
                        "--spring.flyway.password=" + IntegrationTestSupport.OWNER_PASSWORD,
                        "--spring.data.redis.host=" + IntegrationTestSupport.REDIS.getHost(),
                        "--spring.data.redis.port=" + IntegrationTestSupport.REDIS.getMappedPort(6379),
                        "--nexusops.cli.command=make-coffee",
                        "--nexusops.cli.email=ops@nexusops.test");
        assertThat(SpringApplication.exit(context)).isEqualTo(2); // usage
    }
}
