package com.recrutment.gatewayserver.admin;

import com.recrutment.gatewayserver.admin.dto.KcDtos.KcRole;
import com.recrutment.gatewayserver.admin.dto.KcDtos.KcUser;
import com.recrutment.gatewayserver.admin.dto.KcDtos.KcUserUpdate;
import com.recrutment.gatewayserver.admin.dto.KcDtos.TokenResponse;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class KeycloakAdminClient {

    private final KeycloakAdminProperties props;
    private final WebClient webClient;

    private volatile String cachedToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;
    private static final Duration SAFETY = Duration.ofSeconds(15);

    public KeycloakAdminClient(KeycloakAdminProperties props) {
        this.props = props;
        this.webClient = WebClient.builder().build();
    }

    private String adminBase() {
        return props.baseUrl() + "/admin/realms/" + props.realm();
    }

    private Mono<String> serviceToken() {
        Instant now = Instant.now();
        if (cachedToken != null && now.isBefore(tokenExpiresAt.minus(SAFETY))) {
            return Mono.just(cachedToken);
        }
        return requestNewToken();
    }

    private Mono<String> requestNewToken() {
        String tokenUrl = props.baseUrl()
                + "/realms/" + props.realm()
                + "/protocol/openid-connect/token";

        return webClient.post()
                .uri(tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("grant_type", "client_credentials")
                        .with("client_id", props.clientId())
                        .with("client_secret", props.clientSecret()))
                .retrieve()
                .bodyToMono(TokenResponse.class)
                .map(tr -> {
                    this.cachedToken = tr.access_token();
                    this.tokenExpiresAt = Instant.now().plusSeconds(tr.expires_in());
                    return tr.access_token();
                });
    }

    public Mono<List<KcUser>> listUsers(int first, int max, String search) {
        var builder = UriComponentsBuilder
                .fromUriString(adminBase() + "/users")
                .queryParam("first", first)
                .queryParam("max", max);

        Optional.ofNullable(search)
                .filter(s -> !s.isBlank())
                .ifPresent(s -> builder.queryParam("search", s));

        return serviceToken().flatMap(token ->
                webClient.get()
                        .uri(builder.toUriString())
                        .headers(h -> h.setBearerAuth(token))
                        .retrieve()
                        .bodyToMono(new ParameterizedTypeReference<List<KcUser>>() {})
        );
    }

    public Mono<KcUser> getUser(String userId) {
        return serviceToken().flatMap(token ->
                webClient.get()
                        .uri(adminBase() + "/users/{id}", userId)
                        .headers(h -> h.setBearerAuth(token))
                        .retrieve()
                        .bodyToMono(KcUser.class)
        );
    }

    public Mono<List<KcRole>> getUserRealmRoles(String userId) {
        return serviceToken().flatMap(token ->
                webClient.get()
                        .uri(adminBase() + "/users/{id}/role-mappings/realm/composite", userId)
                        .headers(h -> h.setBearerAuth(token))
                        .retrieve()
                        .bodyToMono(new ParameterizedTypeReference<List<KcRole>>() {})
        );
    }

    public Mono<List<KcRole>> listRealmRoles() {
        return serviceToken().flatMap(token ->
                webClient.get()
                        .uri(adminBase() + "/roles")
                        .headers(h -> h.setBearerAuth(token))
                        .retrieve()
                        .bodyToMono(new ParameterizedTypeReference<List<KcRole>>() {})
        );
    }

    public Mono<Void> addRealmRoles(String userId, List<KcRole> roles) {
        return serviceToken().flatMap(token ->
                webClient.post()
                        .uri(adminBase() + "/users/{id}/role-mappings/realm", userId)
                        .headers(h -> h.setBearerAuth(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(roles)
                        .retrieve()
                        .bodyToMono(Void.class)
        );
    }

    public Mono<Void> removeRealmRoles(String userId, List<KcRole> roles) {
        return serviceToken().flatMap(token ->
                webClient.method(org.springframework.http.HttpMethod.DELETE)
                        .uri(adminBase() + "/users/{id}/role-mappings/realm", userId)
                        .headers(h -> h.setBearerAuth(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(roles)
                        .retrieve()
                        .bodyToMono(Void.class)
        );
    }

    public Mono<Void> setUserEnabled(String userId, boolean enabled) {
        return serviceToken().flatMap(token ->
                webClient.put()
                        .uri(adminBase() + "/users/{id}", userId)
                        .headers(h -> h.setBearerAuth(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(new KcUserUpdate(enabled))
                        .retrieve()
                        .bodyToMono(Void.class)
        );
    }

    public Mono<Void> deleteUser(String userId) {
        return serviceToken().flatMap(token ->
                webClient.delete()
                        .uri(adminBase() + "/users/{id}", userId)
                        .headers(h -> h.setBearerAuth(token))
                        .retrieve()
                        .bodyToMono(Void.class)
        );
    }

    /**
     * Creates a new user in Keycloak.
     * The user is created with enabled=true and no password.
     * requiredActions will force them to set their password via email.
     * Returns the new user's ID extracted from the Location header.
     */
    public Mono<String> createUser(String firstName, String lastName, String email, String username) {
        Map<String, Object> body = Map.of(
                "firstName",     firstName,
                "lastName",      lastName,
                "email",         email,
                "username",      username,
                "enabled",       true,
                "emailVerified", false,
                "credentials",   List.of(),
                "requiredActions", List.of("UPDATE_PASSWORD", "VERIFY_EMAIL")
        );

        return serviceToken().flatMap(token ->
                webClient.post()
                        .uri(adminBase() + "/users")
                        .headers(h -> h.setBearerAuth(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .retrieve()
                        .onStatus(status -> status.value() == 409, response ->
                                Mono.error(new org.springframework.web.server.ResponseStatusException(
                                        org.springframework.http.HttpStatus.CONFLICT,
                                        "A user with this email already exists."
                                ))
                        )
                        .toBodilessEntity()
                        .map(response -> {
                            // Keycloak returns 201 with Location: .../users/{id}
                            var location = response.getHeaders().getLocation();
                            if (location == null) throw new RuntimeException("No Location header in Keycloak response");
                            String path = location.getPath();
                            return path.substring(path.lastIndexOf('/') + 1);
                        })
        );
    }

    /**
     * Sends an invite email to the user with actions to set their password.
     * Called after createUser() to trigger the email invitation flow.
     */
    public Mono<Void> sendInviteEmail(String userId) {
        List<String> actions = List.of("UPDATE_PASSWORD", "VERIFY_EMAIL");

        String url = adminBase() + "/users/" + userId + "/execute-actions-email"
                + "?client_id=hireAI-frontend&redirect_uri=http://localhost:4200";
        return serviceToken().flatMap(token ->
                webClient.put()
                        .uri(url)
                        .headers(h -> h.setBearerAuth(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(actions)
                        .retrieve()
                        .bodyToMono(Void.class)
        );
    }

    public Mono<Long> countUsers(String search) {
        var builder = UriComponentsBuilder
                .fromUriString(adminBase() + "/users/count");

        Optional.ofNullable(search)
                .filter(s -> !s.isBlank())
                .ifPresent(s -> builder.queryParam("search", s));

        return serviceToken().flatMap(token ->
                webClient.get()
                        .uri(builder.toUriString())
                        .headers(h -> h.setBearerAuth(token))
                        .retrieve()
                        .bodyToMono(Long.class)
        );
    }
}