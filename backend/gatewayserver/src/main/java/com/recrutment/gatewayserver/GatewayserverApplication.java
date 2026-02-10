package com.recrutment.gatewayserver;

import com.recrutment.gatewayserver.admin.KeycloakAdminProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(KeycloakAdminProperties.class)
public class GatewayserverApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayserverApplication.class, args);
    }
    @Bean
    public RouteLocator MyRouteConfig(RouteLocatorBuilder routeLocatorBuilder)
    {
        return routeLocatorBuilder.routes()
                .route("application-microservice", r -> r
                .path("/api/applications/**")
                .uri("lb://application-microservice"))
                .route("job-microservice", r -> r
                .path("/api/jobs/**")
                .uri("lb://job-microservice"))
                .build();
    }
}