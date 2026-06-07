package com.recrutment.application.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                // Compose healthcheck probes /actuator/health every 15s; the
                // existing JWT chain would 401 anonymous probes and the
                // container would flap to unhealthy. Keep these open.
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/api/applications/internal/**").permitAll()
                // Service-to-service: interview-service hits cv-summary on
                // every schedule / retrigger. No user context exists for that
                // call so we don't gate it behind a bearer token.
                .requestMatchers("/api/applications/*/cv-summary").permitAll()
                // Catalog intel + resolve are consumed by cv-parser-service
                // during matching and backfill. Internal-only paths inside
                // the docker network — no user JWT to propagate.
                .requestMatchers("/api/applications/skill-catalog/*/intel").permitAll()
                .requestMatchers("/api/applications/skill-catalog/resolve").permitAll()
                .requestMatchers("/api/applications/skill-catalog/backfill-embeddings").permitAll()
                // Track 1 proxy search — called once per requirement during matching.
                .requestMatchers("/api/applications/skill-catalog/nearest-of").permitAll()
                // Matcher reads the SOFT signal list (with vectors) at the start of each match.
                .requestMatchers("/api/applications/skill-catalog/by-type/*").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));
        return http.build();
    }
}
