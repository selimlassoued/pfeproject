package com.zaina.jobmicroservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Defense-in-depth security for job-microservice.
 *
 * The gateway already enforces role-aware rules on /api/jobs/**. We
 * mirror that here with "any authenticated JWT", so a caller that
 * bypasses the gateway (Docker-network reach, debug port forward,
 * misrouted call) still has to present a real Keycloak token.
 *
 * Per-handler role / ownership checks live in the controllers and
 * services; this config's job is to gate anonymous traffic.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // Compose healthcheck probe.
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}));
        return http.build();
    }
}
