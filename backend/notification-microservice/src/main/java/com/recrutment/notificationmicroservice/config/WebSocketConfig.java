package com.recrutment.notificationmicroservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.security.Principal;

/**
 * STOMP / SockJS setup.
 *
 * Per-user routing requires the WS session to carry a Principal. The
 * gateway authenticates the upgrade and Spring Security puts the JWT
 * subject in the request context, but Spring's default WS plumbing
 * does NOT promote that to a SimpUser. We do it explicitly in the
 * inbound channel interceptor below: on STOMP CONNECT we read the
 * Authorization header, decode the JWT with NimbusJwtDecoder (same
 * JWKs the resource-server filter uses), and stamp the JWT subject
 * onto the session as a Principal. Once that's set,
 * convertAndSendToUser(sub, "/queue/X", ...) routes correctly and the
 * /user/queue/X destination is scoped to the right session.
 *
 * On every STOMP SUBSCRIBE we also reject attempts to subscribe to a
 * topic that names another user (e.g. /topic/notifications.{otherSub}).
 * Closes the existing pattern's cross-user leak.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtDecoder jwtDecoder;

    public WebSocketConfig(@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri) {
        this.jwtDecoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/notifications")
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new StompAuthInterceptor(jwtDecoder));
    }

    /**
     * Reads Bearer on CONNECT, stamps the JWT subject as the session
     * Principal, and rejects SUBSCRIBE to another user's topic.
     */
    static class StompAuthInterceptor implements ChannelInterceptor, Ordered {

        private final JwtDecoder jwtDecoder;
        StompAuthInterceptor(JwtDecoder jwtDecoder) { this.jwtDecoder = jwtDecoder; }

        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
                    message, StompHeaderAccessor.class);
            if (accessor == null) return message;
            StompCommand command = accessor.getCommand();
            if (command == null) return message;

            switch (command) {
                case CONNECT -> {
                    String auth = accessor.getFirstNativeHeader("Authorization");
                    String sub = subjectFromBearer(auth);
                    if (sub == null) {
                        // Anonymous CONNECT - reject by returning null.
                        return null;
                    }
                    accessor.setUser(new StompPrincipal(sub));
                    accessor.setLeaveMutable(true);
                }
                case SUBSCRIBE -> {
                    Principal principal = accessor.getUser();
                    String dest = accessor.getDestination();
                    if (principal == null) return null;
                    if (dest != null && !destinationOwnedByPrincipal(dest, principal.getName())) {
                        // Reject cross-user topic subscriptions.
                        return null;
                    }
                }
                default -> { /* SEND / DISCONNECT / others: no-op */ }
            }
            return message;
        }

        @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

        private String subjectFromBearer(String authHeader) {
            if (authHeader == null) return null;
            String prefix = "Bearer ";
            if (!authHeader.startsWith(prefix)) return null;
            String token = authHeader.substring(prefix.length()).trim();
            if (token.isEmpty()) return null;
            try {
                Jwt jwt = jwtDecoder.decode(token);
                return jwt.getSubject();
            } catch (Exception e) {
                return null;
            }
        }

        /**
         * The existing pattern is /topic/X.{userId} (e.g.
         * /topic/notifications.{sub}, /topic/offers.{sub},
         * /topic/interviews.list.{sub}). Subscribing to a different
         * userId means listening to someone else's traffic, so we
         * require the suffix to equal the caller's sub. /user/** is
         * already scoped by Spring per-session, so we allow it. /queue
         * destinations are session-scoped by the broker too.
         */
        private static boolean destinationOwnedByPrincipal(String destination, String sub) {
            if (destination.startsWith("/user/")) return true;
            if (destination.startsWith("/queue/")) return true;
            if (!destination.startsWith("/topic/")) return true;
            int dot = destination.lastIndexOf('.');
            if (dot < 0) return true;
            String suffix = destination.substring(dot + 1);
            return suffix.equals(sub);
        }
    }

    /** Minimal Principal so accessor.setUser sticks for the session. */
    record StompPrincipal(String name) implements Principal {
        @Override public String getName() { return name; }
    }
}
