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
 * HTTP endpoints (/api/notifications/**) require a valid Keycloak JWT.
 *
 * The STOMP WebSocket handshake (/ws/notifications/**) now also requires
 * authentication. The gateway's WebSocketTokenRelayFilter promotes the
 * frontend's ?access_token= query param to an Authorization: Bearer
 * header before this rule runs, so the same oauth2ResourceServer chain
 * validates both HTTP and WS requests. WebSocketAuthConfig then binds
 * the JWT's sub claim to the STOMP session as its Principal so
 * convertAndSendToUser and per-user destination scoping work.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}));
        return http.build();
    }
}
