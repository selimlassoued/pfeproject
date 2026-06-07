package com.recrutment.gatewayserver.admin.service;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * @LoadBalanced WebClient.Builder so the gateway's own admin handlers can
 * call other Eureka-registered services with http://SERVICE-ID/path URIs
 * instead of hardcoded hostnames. Consumers inject the builder and call
 * .build() per use; the LoadBalancer filter is wired on the builder, not
 * the resulting WebClient instances.
 *
 * KeycloakAdminClient deliberately constructs its own WebClient without
 * this builder because Keycloak is not a Eureka-registered service.
 */
@Configuration
public class WebClientConfig {

    @Bean
    @LoadBalanced
    public WebClient.Builder loadBalancedWebClientBuilder() {
        return WebClient.builder();
    }
}
