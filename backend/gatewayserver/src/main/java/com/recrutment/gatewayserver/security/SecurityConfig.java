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
import reactor.core.publisher.Mono;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

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
        config.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }


    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity serverHttpSecurity) {
        serverHttpSecurity
                .authorizeExchange(exchanges -> //exchanges.pathMatchers(HttpMethod.GET).authenticated()
                        exchanges.pathMatchers("/actuator/health/**","/actuator/info").permitAll()
                                .pathMatchers("/actuator/**").hasRole("HR")
                                .pathMatchers("/api/admin/**").hasRole("ADMIN")

                                .pathMatchers(HttpMethod.GET, "/api/jobs/**").permitAll()

                .pathMatchers(HttpMethod.POST, "/api/jobs/**").hasAnyRole("HR", "ADMIN")
                .pathMatchers(HttpMethod.PUT, "/api/jobs/**").hasAnyRole("HR", "ADMIN")
                .pathMatchers(HttpMethod.PATCH, "/api/jobs/**").hasAnyRole("HR", "ADMIN")
                .pathMatchers(HttpMethod.DELETE, "/api/jobs/**").hasAnyRole("HR", "ADMIN")
                .pathMatchers(HttpMethod.POST, "/api/applications/**").hasRole("CANDIDATE")
                .pathMatchers(HttpMethod.GET, "/api/applications/me/**").hasRole("CANDIDATE")
                .pathMatchers(HttpMethod.PATCH, "/api/applications/me/**").hasRole("CANDIDATE")

                .pathMatchers(HttpMethod.GET, "/api/applications/*/cv").hasAnyRole("HR","ADMIN")
                 .pathMatchers(HttpMethod.GET, "/api/applications/**").hasAnyRole("HR","ADMIN")

                .pathMatchers(HttpMethod.PUT, "/api/applications/**").hasAnyRole("HR","ADMIN")
                .pathMatchers(HttpMethod.PATCH, "/api/applications/**").hasAnyRole("HR","ADMIN")

                .pathMatchers(HttpMethod.DELETE, "/api/applications/**").hasAnyRole("HR","ADMIN"))

                .oauth2ResourceServer(oauth2 -> oauth2
                        //.jwt(Customizer.withDefaults())
                        .jwt(jwtSpec -> jwtSpec.jwtAuthenticationConverter(grantedAuthoritiesExtractor()))
                );

        serverHttpSecurity.csrf(csrf -> csrf.disable());
        return serverHttpSecurity.build();
    }
//    @Bean
//    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity serverHttpSecurity) {
//        serverHttpSecurity
//                .authorizeExchange(exchanges ->
//                        exchanges.anyExchange().permitAll()
//                )
//                .csrf(csrf -> csrf.disable());
//
//        return serverHttpSecurity.build();
//    }


    private Converter<Jwt, Mono<AbstractAuthenticationToken>>
    grantedAuthoritiesExtractor() {
        JwtAuthenticationConverter jwtAuthenticationConverter =
                new JwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter
                (new KeycloackRoleConverter());
        return new ReactiveJwtAuthenticationConverterAdapter(jwtAuthenticationConverter);
    }
}