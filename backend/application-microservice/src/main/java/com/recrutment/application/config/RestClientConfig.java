package com.recrutment.application.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.ClientHttpRequestInterceptor;

@Configuration
public class RestClientConfig {

    @Bean
    public RestTemplate restTemplate() {

        RestTemplate rt = new RestTemplate();

        ClientHttpRequestInterceptor authInterceptor = (request, body, execution) -> {

            var auth = SecurityContextHolder.getContext().getAuthentication();

            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.getTokenValue());
            }

            return execution.execute(request, body);
        };

        rt.getInterceptors().add(authInterceptor);

        return rt;
    }
}
