package com.recrutment.gatewayserver.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

import java.util.List;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder() {
        return NimbusReactiveJwtDecoder
                .withJwkSetUri("http://keycloak:8080/realms/ai-recruitment/protocol/openid-connect/certs")
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:4200"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeExchange(exchanges -> exchanges

                        // Actuator
                        .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers("/actuator/**").hasRole("RECRUITER")

                        // Admin
                        .pathMatchers(HttpMethod.GET, "/api/admin/internal/users/*/email").permitAll()
                        .pathMatchers("/api/admin/**").hasRole("ADMIN")

                        // Jobs
                        .pathMatchers(HttpMethod.GET, "/api/jobs/**").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/jobs/**").hasAnyRole("RECRUITER", "ADMIN")
                        .pathMatchers(HttpMethod.PUT, "/api/jobs/**").hasAnyRole("RECRUITER", "ADMIN")
                        .pathMatchers(HttpMethod.PATCH, "/api/jobs/**").hasAnyRole("RECRUITER", "ADMIN")
                        .pathMatchers(HttpMethod.DELETE, "/api/jobs/**").hasAnyRole("RECRUITER", "ADMIN")

                        .pathMatchers(HttpMethod.GET, "/api/applications/*/analysis").hasAnyRole("RECRUITER", "ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/applications/*/analysis/exists").hasAnyRole("RECRUITER", "ADMIN")

                        .pathMatchers(HttpMethod.GET, "/api/applications/internal/job/*/candidate-ids").permitAll()

                        // Applications - candidate "me" endpoints (IMPORTANT: must be before /api/applications/**)
                        .pathMatchers(HttpMethod.POST, "/api/applications/**").hasRole("CANDIDATE")
                        .pathMatchers(HttpMethod.GET, "/api/applications/me").hasRole("CANDIDATE")
                        .pathMatchers(HttpMethod.GET, "/api/applications/me/**").hasRole("CANDIDATE")   // ✅ FIX
                        .pathMatchers(HttpMethod.PATCH, "/api/applications/me/**").hasRole("CANDIDATE")

                        // Applications - recruiter/admin
                        .pathMatchers(HttpMethod.GET, "/api/applications/*/cv").hasAnyRole("RECRUITER", "ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/applications/**").hasAnyRole("RECRUITER", "ADMIN")
                        .pathMatchers(HttpMethod.PUT, "/api/applications/**").hasAnyRole("RECRUITER", "ADMIN")
                        .pathMatchers(HttpMethod.PATCH, "/api/applications/**").hasAnyRole("RECRUITER", "ADMIN")
                        .pathMatchers(HttpMethod.DELETE, "/api/applications/**").hasAnyRole("RECRUITER", "ADMIN")

                        // Notifications
                        .pathMatchers(HttpMethod.GET, "/api/notifications/**").hasRole("CANDIDATE")
                        .pathMatchers(HttpMethod.POST, "/api/notifications/**").hasRole("CANDIDATE")
                        .pathMatchers(HttpMethod.PUT, "/api/notifications/**").hasRole("CANDIDATE")
                        .pathMatchers(HttpMethod.PATCH, "/api/notifications/**").hasRole("CANDIDATE")

                        // WebSocket
                        .pathMatchers("/ws/notifications/**").permitAll()
                        .pathMatchers("/api/audit/**").hasRole("ADMIN")

                        // Everything else
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(grantedAuthoritiesExtractor()))
                );

        return http.build();
    }

    private Converter<Jwt, Mono<AbstractAuthenticationToken>> grantedAuthoritiesExtractor() {
        JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(new KeycloackRoleConverter());
        return new ReactiveJwtAuthenticationConverterAdapter(jwtAuthenticationConverter);
    }
}