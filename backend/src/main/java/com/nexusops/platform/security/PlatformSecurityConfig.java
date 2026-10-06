package com.nexusops.platform.security;

import com.nexusops.platform.PlatformProperties;
import com.nexusops.shared.security.ProblemDetailSecurityHandlers;
import com.nexusops.shared.web.PublicEndpoints;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The platform security chain (ADR-0007): ordered before the tenant chain and matching only /api/v1/platform/**.
 * Its JWT decoder accepts only platform-audience tokens; the tenant chain's decoder accepts only tenant tokens.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class PlatformSecurityConfig {

    static final String PLATFORM_PATHS = "/api/v1/platform/**";

    @Bean
    @Order(1)
    SecurityFilterChain platformSecurity(HttpSecurity http, ProblemDetailSecurityHandlers handlers,
            PlatformPrincipalFilter principalFilter, RSAKey jwtRsaKey, PlatformProperties properties,
            @Value("${nexusops.security.jwt.issuer}") String issuer) throws Exception {
        http.securityMatcher(PLATFORM_PATHS)
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PublicEndpoints.matchers()).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenResolver(new PlatformBearerTokenResolver())
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers)
                        .jwt(jwt -> jwt.decoder(PlatformJwt.decoder(jwtRsaKey, issuer, properties.audience()))))
                .exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
                .addFilterAfter(principalFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /** PlatformPrincipalFilter belongs to the platform security chain only, not the servlet filter chain. */
    @Bean
    FilterRegistrationBean<PlatformPrincipalFilter> platformPrincipalFilterServletRegistration(PlatformPrincipalFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
