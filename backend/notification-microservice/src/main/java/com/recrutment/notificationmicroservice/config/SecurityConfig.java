package com.recrutment.notificationmicroservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Defense-in-depth security for notification-microservice.
 *
 * HTTP endpoints (/api/notifications/**) now require a valid Keycloak
 * JWT. Before, the service had no security config and any container on
 * the Docker network could mark anyone's notifications read.
 *
 * The STOMP WebSocket endpoint (/ws/notifications/**) is left as
 * permitAll for parity with the gateway, which also can't enforce auth
 * on it without frontend token plumbing in the WS handshake. See the
 * TODO in gatewayserver/SecurityConfig for what flipping that on
 * needs.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // WebSocket handshake + SockJS fallback. Left open
                        // until the frontend appends ?access_token= to the
                        // brokerURL and we relay it as Authorization here.
                        .requestMatchers("/ws/notifications/**").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}));
        return http.build();
    }
}
