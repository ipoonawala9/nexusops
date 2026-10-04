package com.nexusops.shared.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RequiredSecretsVerifierTest {

    @Test
    void passesWhenAllSecretsPresent() {
        var env = new MockEnvironment()
                .withProperty("spring.datasource.password", "a")
                .withProperty("spring.flyway.password", "b");
        assertThatCode(() -> RequiredSecretsVerifier.verify(env)).doesNotThrowAnyException();
    }

    @Test
    void failsNamingTheEnvironmentVariableWhenBlank() {
        var env = new MockEnvironment()
                .withProperty("spring.datasource.password", "a")
                .withProperty("spring.flyway.password", " ");
        assertThatThrownBy(() -> RequiredSecretsVerifier.verify(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_OWNER_PASSWORD");
    }

    @Test
    void failsWhenPlaceholderIsUnresolved() {
        var env = new MockEnvironment()
                .withProperty("spring.datasource.password", "${DB_APP_PASSWORD}")
                .withProperty("spring.flyway.password", "b");
        assertThatThrownBy(() -> RequiredSecretsVerifier.verify(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_APP_PASSWORD");
    }
}
