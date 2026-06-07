package com.recrutment.application.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.ClientHttpRequestInterceptor;

/**
 * RestTemplate beans for service-to-service calls.
 *
 * Both beans carry @LoadBalanced so http://SERVICE-ID/path URIs are
 * resolved through Eureka + Spring Cloud LoadBalancer instead of
 * routed through the gateway. That removes the gateway as a SPOF for
 * internal traffic and lets us scale a backend service horizontally
 * without rewiring its callers.
 *
 * - restTemplate: forwards the caller's user JWT if there is one in
 *   the security context (used for calls that act on behalf of a
 *   logged-in user)
 * - plainRestTemplate: no auth interceptor (used for fire-and-forget
 *   background calls between services)
 */
@Configuration
public class RestClientConfig {

    @Bean
    @LoadBalanced
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
    @Bean("plainRestTemplate")
    @LoadBalanced
    public RestTemplate plainRestTemplate() {
        return new RestTemplate(); // sans JWT — pour services internes
    }
}
