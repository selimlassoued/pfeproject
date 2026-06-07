package com.zaina.interviewservice.config;

import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AppConfig {

    /**
     * @LoadBalanced so http://SERVICE-ID/path URIs resolve through
     * Eureka. Service-to-service calls (e.g. application-microservice,
     * user lookups) skip the gateway and go direct to the registered
     * instance.
     */
    @Bean
    @LoadBalanced
    public RestTemplate restTemplate() {
        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault());
        return new RestTemplate(factory);
    }
}