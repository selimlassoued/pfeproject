package com.recrutment.gatewayserver.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * Stamps trusted identity headers on every request before it's forwarded
 * to a downstream service:
 *
 *   X-Actor-User-Id  — JWT subject
 *   X-Actor-Roles    — highest realm role the user has, picked from the
 *                      ranked hierarchy SUPERADMIN > ADMIN > RECRUITER >
 *                      CANDIDATE. Audit / admin endpoints rely on this
 *                      to decide what the caller is allowed to see.
 *
 * Both headers are stripped from the incoming request before re-adding,
 * so a client sending its own X-Actor-User-Id or X-Actor-Roles cannot
 * impersonate another user or escalate their role.
 */
@Component
public class ActorUserIdGatewayFilter implements GlobalFilter, Ordered {

    public static final String ACTOR_USER_ID_HEADER = "X-Actor-User-Id";
    public static final String ACTOR_ROLES_HEADER   = "X-Actor-Roles";

    /** Highest-first so the first match wins. */
    private static final String[] ROLE_HIERARCHY = { "SUPERADMIN", "ADMIN", "RECRUITER", "CANDIDATE" };

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .cast(Authentication.class)
                .flatMap(auth -> {
                    String userId = auth.getName();
                    String topRole = topRole(auth.getAuthorities());

                    ServerWebExchange mutated = exchange.mutate()
                            .request(r -> r.headers(h -> {
                                // Strip whatever the client tried to send, then
                                // add the values we computed from the JWT.
                                h.remove(ACTOR_USER_ID_HEADER);
                                h.remove(ACTOR_ROLES_HEADER);
                                if (userId != null && !userId.isBlank()) {
                                    h.add(ACTOR_USER_ID_HEADER, userId);
                                }
                                if (topRole != null) {
                                    h.add(ACTOR_ROLES_HEADER, topRole);
                                }
                            }))
                            .build();
                    return chain.filter(mutated);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    // No principal: still strip any spoofed headers before
                    // forwarding. The gateway's own auth rules will decide
                    // whether the request is allowed through.
                    ServerWebExchange mutated = exchange.mutate()
                            .request(r -> r.headers(h -> {
                                h.remove(ACTOR_USER_ID_HEADER);
                                h.remove(ACTOR_ROLES_HEADER);
                            }))
                            .build();
                    return chain.filter(mutated);
                }));
    }

    private static String topRole(java.util.Collection<? extends GrantedAuthority> authorities) {
        Set<String> have = new java.util.HashSet<>();
        for (GrantedAuthority a : authorities) {
            String s = a.getAuthority();
            if (s == null) continue;
            // Spring Security exposes realm roles as "ROLE_SUPERADMIN" etc.
            if (s.startsWith("ROLE_")) s = s.substring("ROLE_".length());
            have.add(s);
        }
        for (String r : ROLE_HIERARCHY) {
            if (have.contains(r)) return r;
        }
        return null;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1; // run after security
    }
}
