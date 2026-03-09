package com.recrutment.notificationmicroservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Configuration
public class HttpClientConfig {

    @Bean
    public RestTemplate restTemplate(
            @Value("${app.security.keycloak.token-url}") String tokenUrl,
            @Value("${app.security.keycloak.client-id}") String clientId,
            @Value("${app.security.keycloak.client-secret}") String clientSecret
    ) {
        RestTemplate rt = new RestTemplate();

        ClientHttpRequestInterceptor authInterceptor = (request, body, execution) -> {

            // 1) try user JWT (if exists)
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                request.getHeaders().setBearerAuth(jwt.getTokenValue());
                return execution.execute(request, body);
            }

            // 2) fallback: client_credentials token from Keycloak
            // IMPORTANT: use a RAW RestTemplate (no interceptors) to avoid recursion
            RestTemplate raw = new RestTemplate();

            var headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            var form = new LinkedMultiValueMap<String, String>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);

            Map<?, ?> tokenResp = raw.postForObject(
                    tokenUrl,
                    new org.springframework.http.HttpEntity<>(form, headers),
                    Map.class
            );

            String token = tokenResp != null ? (String) tokenResp.get("access_token") : null;
            if (token != null) request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + token);

            return execution.execute(request, body);
        };

        rt.getInterceptors().add(authInterceptor);
        return rt;
    }
}