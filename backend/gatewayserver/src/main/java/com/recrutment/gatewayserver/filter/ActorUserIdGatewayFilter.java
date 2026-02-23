package com.recrutment.gatewayserver.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Adds X-Actor-User-Id request header with the JWT subject (user id) for downstream services.
 * Runs after security so the principal is available when present.
 */
@Component
public class ActorUserIdGatewayFilter implements GlobalFilter, Ordered {

    public static final String ACTOR_USER_ID_HEADER = "X-Actor-User-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .map(principal -> principal.getName())
                .flatMap(userId -> {
                    ServerWebExchange mutated = exchange.mutate()
                            .request(r -> r.header(ACTOR_USER_ID_HEADER, userId))
                            .build();
                    return chain.filter(mutated);
                })
                .switchIfEmpty(chain.filter(exchange));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1; // run after security
    }
}
