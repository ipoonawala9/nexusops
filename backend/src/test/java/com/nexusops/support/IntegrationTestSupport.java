package com.nexusops.support;

import java.nio.file.Path;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Base for integration tests. Starts Postgres (bootstrapped with the SAME role script as local
 * compose) and Redis once per JVM. The app connects as nexusops_app, never as the superuser,
 * so RLS behaves exactly as in production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(TestBeans.class)
public abstract class IntegrationTestSupport {

    public static final String OWNER_PASSWORD = "owner_test";
    public static final String APP_PASSWORD = "app_test";

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("nexusops")
            .withUsername("postgres")
            .withPassword("postgres_test")
            .withEnv("NEXUSOPS_OWNER_PASSWORD", OWNER_PASSWORD)
            .withEnv("NEXUSOPS_APP_PASSWORD", APP_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("../infra/docker/postgres/init/01-roles.sh"), 0755),
                    "/docker-entrypoint-initdb.d/01-roles.sh");

    @SuppressWarnings("resource")
    public static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "nexusops_app");
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", () -> "nexusops_owner");
        registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
