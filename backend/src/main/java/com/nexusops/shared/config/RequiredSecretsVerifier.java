package com.nexusops.shared.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails startup before any DataSource/Flyway bean is created when a required secret is missing.
 * Spring's binder passes unresolved {@code ${...}} placeholders through literally, which would
 * otherwise surface much later as a misleading authentication error.
 */
@Component
class RequiredSecretsVerifier implements BeanFactoryPostProcessor, EnvironmentAware {

    /** property → environment variable that supplies it. Order defines which is reported first. */
    private static final Map<String, String> REQUIRED = new LinkedHashMap<>();

    static {
        REQUIRED.put("spring.datasource.password", "DB_APP_PASSWORD");
        REQUIRED.put("spring.flyway.password", "DB_OWNER_PASSWORD");
        REQUIRED.put("nexusops.security.allowed-origins", "ALLOWED_ORIGINS");
    }

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        verify(environment);
    }

    static void verify(Environment environment) {
        REQUIRED.forEach((property, envVar) -> {
            String value;
            try {
                value = environment.getProperty(property);
            } catch (IllegalArgumentException unresolvedPlaceholder) {
                value = null;
            }
            if (value == null || value.isBlank() || value.contains("${")) {
                throw new IllegalStateException("Missing required secret: set environment variable "
                        + envVar + " (" + property + ").");
            }
        });
    }
}
