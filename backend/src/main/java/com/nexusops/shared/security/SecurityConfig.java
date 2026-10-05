package com.nexusops.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Baseline: stateless, deny-by-default. Plan 2 adds the JWT resource server and the auth routes.
 * CSRF is disabled because the API authenticates with bearer tokens; the cookie-based
 * /api/v1/auth/refresh endpoint gets SameSite=Strict + Origin checks in Plan 2.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    public static final String[] PUBLIC_PATHS = {
        "/actuator/health", "/actuator/health/**", "/actuator/info",
        "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**",
        "/error"
    };

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ProblemDetailSecurityHandlers handlers) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(com.nexusops.shared.web.PublicEndpoints.matchers()).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers));
        return http.build();
    }
}
