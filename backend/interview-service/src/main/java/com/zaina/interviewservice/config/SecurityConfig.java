package com.zaina.interviewservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Defense-in-depth security for interview-service.
 *
 * The gateway already gates /api/interviews/** with role-aware rules, so
 * we mirror that with "any authenticated JWT" here rather than duplicate
 * the per-path role matrix. If the gateway is ever bypassed (someone hits
 * the service directly over the Docker network), this filter chain stops
 * anonymous access.
 *
 * Per-handler role and ownership checks (e.g. "the calling recruiter
 * owns this interview") live in the controllers and services. The point
 * of this config is to make sure those handlers only ever see callers
 * who hold a real Keycloak JWT.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // Spring Boot actuator probes for compose healthchecks.
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}));
        return http.build();
    }
}
