package com.recrutment.gatewayserver.filter;

import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Promotes ?access_token=&lt;jwt&gt; on the WebSocket upgrade request to an
 * Authorization: Bearer header so Spring Security's existing
 * oauth2ResourceServer().jwt() chain can validate the token the same way
 * it does for HTTP requests.
 *
 * STOMP over WebSocket can't send an Authorization header from a browser
 * (the WebSocket API has no way to set custom request headers cross-
 * origin), so the canonical workaround is to carry the token in the
 * query string and translate it server-side. This filter does that
 * translation only for the /ws/notifications/** path and only when no
 * Authorization header is already present, so HTTP routes are
 * unaffected.
 *
 * IMPORTANT: this is a Spring WebFlux WebFilter (NOT a Spring Cloud
 * Gateway GlobalFilter). GlobalFilters run AFTER Spring Security in
 * WebFlux, which means any /ws/notifications/** request would be
 * rejected by Security with 401 BEFORE the filter had a chance to
 * promote the query token. A WebFilter at HIGHEST_PRECEDENCE runs
 * before the Spring Security WebFilter (which sits at order -100 via
 * WebHttpHandlerBuilder), so the Authorization header is in place by
 * the time Security inspects the request.
 */
@Component
public class WebSocketTokenRelayFilter implements WebFilter, Ordered {

    private static final String WS_PATH_PREFIX = "/ws/notifications";
    private static final String ACCESS_TOKEN_PARAM = "access_token";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        if (path == null || !path.startsWith(WS_PATH_PREFIX)) {
            return chain.filter(exchange);
        }

        // If a downstream client already presented Authorization, keep that.
        if (request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION) != null) {
            return chain.filter(exchange);
        }

        List<String> tokens = request.getQueryParams().get(ACCESS_TOKEN_PARAM);
        if (tokens == null || tokens.isEmpty() || tokens.get(0) == null || tokens.get(0).isBlank()) {
            return chain.filter(exchange);
        }

        String bearer = "Bearer " + tokens.get(0);
        ServerHttpRequest mutated = request.mutate()
                .headers(h -> h.set(HttpHeaders.AUTHORIZATION, bearer))
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    @Override
    public int getOrder() {
        // Must run BEFORE Spring Security's WebFilter. Spring Security adds
        // its filter at a relatively high precedence; HIGHEST_PRECEDENCE
        // guarantees we run first.
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
