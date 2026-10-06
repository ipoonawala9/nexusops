package com.nexusops.identity.security;

import com.nexusops.shared.security.ProblemDetailSecurityHandlers;
import com.nexusops.shared.web.PublicEndpoints;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/** Stateless, deny-by-default; JWT bearer auth; principal state checked on every request. */
@Configuration(proxyBeanMethods = false)
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
@EnableMethodSecurity
public class SecurityConfig {

    public static final String[] INFRA_PUBLIC_PATHS = {
        "/actuator/health", "/actuator/health/**", "/actuator/info",
        "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**",
        "/error"
    };

    @Bean
    @org.springframework.core.annotation.Order(2)
    SecurityFilterChain apiSecurity(HttpSecurity http, ProblemDetailSecurityHandlers handlers,
            PrincipalFilter principalFilter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(INFRA_PUBLIC_PATHS).permitAll()
                        .requestMatchers(PublicEndpoints.matchers()).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenResolver(new PublicRouteAwareBearerTokenResolver())
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers)
                        .jwt(jwt -> {}))
                .exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
                .addFilterAfter(principalFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /** PrincipalFilter belongs to the security chain only — not also to the servlet filter chain. */
    @Bean
    FilterRegistrationBean<PrincipalFilter> principalFilterServletRegistration(PrincipalFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
