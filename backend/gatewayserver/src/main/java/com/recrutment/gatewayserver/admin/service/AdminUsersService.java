package com.recrutment.gatewayserver.admin.service;

import com.recrutment.gatewayserver.admin.KeycloakAdminClient;
import com.recrutment.gatewayserver.admin.dto.KcDtos.KcRole;
import com.recrutment.gatewayserver.admin.dto.KcDtos.KcUser;
import com.recrutment.gatewayserver.messaging.AppEventMessage;
import com.recrutment.gatewayserver.messaging.AppEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AdminUsersService {

    private final AppEventPublisher eventPublisher;
    private final KeycloakAdminClient kc;
    private final WebClient webClient;
    private final String applicationServiceUrl;

    // SUPERADMIN can assign any role including ADMIN
    private static final Set<String> ALLOWED_BY_SUPERADMIN = Set.of("CANDIDATE", "RECRUITER", "ADMIN", "SUPERADMIN");
    // ADMIN can only assign CANDIDATE and RECRUITER
    private static final Set<String> ALLOWED_BY_ADMIN      = Set.of("CANDIDATE", "RECRUITER");
    // Used for general role fetching
    private static final Set<String> ALLOWED = Set.of("CANDIDATE", "RECRUITER", "ADMIN", "SUPERADMIN");

    public AdminUsersService(
            KeycloakAdminClient kc,
            AppEventPublisher eventPublisher,
            @Value("${app.application-service.url:http://application-microservice:8080}") String applicationServiceUrl
    ) {
        this.kc = kc;
        this.eventPublisher = eventPublisher;
        this.applicationServiceUrl = applicationServiceUrl;
        this.webClient = WebClient.builder().build();
    }

    public Mono<List<KcUser>> listUsers(int first, int max, String search) {
        return kc.listUsers(first, max, search)
                .flatMap(users ->
                        Flux.fromIterable(users)
                                .flatMapSequential(u -> getAllowedRolesSafe(u.id())
                                        .map(roles -> copyWithRoles(u, roles)))
                                .collectList()
                );
    }

    public Mono<KcUser> getProfile(String userId) {
        return Mono.zip(kc.getUser(userId), getAllowedRolesSafe(userId))
                .map(tuple -> copyWithRoles(tuple.getT1(), tuple.getT2()))
                .onErrorResume(e -> {
                    log.warn("[AdminUsersService] getProfile failed for userId={}: {}", userId, e.getMessage());
                    return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "User not found: " + userId));
                });
    }

    public Mono<List<String>> getAllowedRoles(String userId) {
        return kc.getUserRealmRoles(userId)
                .map(roles -> roles.stream()
                        .map(KcRole::name)
                        .filter(ALLOWED::contains)
                        .distinct().sorted().toList());
    }

    private Mono<List<String>> getAllowedRolesSafe(String userId) {
        return getAllowedRoles(userId).onErrorReturn(List.of());
    }

    private KcUser copyWithRoles(KcUser u, List<String> roles) {
        return new KcUser(u.id(), u.username(), u.firstName(), u.lastName(),
                u.email(), u.enabled(), u.createdTimestamp(), u.attributes(), roles);
    }

    // ── Create user (ADMIN creates RECRUITER, SUPERADMIN creates ADMIN/RECRUITER) ──

    /**
     * Creates a new user in Keycloak, assigns the given role,
     * and sends an invite email so they can set their password.
     *
     * @param actorRoles   roles of the actor performing the action
     * @param targetRole   role to assign to the new user (RECRUITER or ADMIN)
     */
    public Mono<KcUser> createUser(String firstName, String lastName, String email,
                                   String targetRole, String actorUserId, List<String> actorRoles) {

        // ADMIN can only create RECRUITER accounts
        boolean isSuperAdmin = actorRoles.stream().anyMatch(r -> r.equalsIgnoreCase("SUPERADMIN"));
        boolean isAdmin      = actorRoles.stream().anyMatch(r -> r.equalsIgnoreCase("ADMIN"));

        if (!isSuperAdmin && isAdmin && !targetRole.equalsIgnoreCase("RECRUITER")) {
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Admins can only create RECRUITER accounts."));
        }
        if (!isSuperAdmin && !isAdmin) {
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed."));
        }

        // Generate username from email (part before @)
        String username = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;

        return kc.createUser(firstName, lastName, email, username)
                .flatMap(newUserId ->
                        // Assign the role
                        kc.listRealmRoles()
                                .map(all -> all.stream()
                                        .filter(r -> r.name().equalsIgnoreCase(targetRole))
                                        .findFirst()
                                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                                "Role not found: " + targetRole)))
                                .flatMap(role -> kc.addRealmRoles(newUserId, List.of(role)))
                                .then(kc.sendInviteEmail(newUserId))
                                // Delay to let Keycloak index the new user, then fetch profile
                                .then(Mono.defer(() ->
                                        getProfile(newUserId)
                                                .delaySubscription(java.time.Duration.ofMillis(600))
                                                .onErrorResume(e -> {
                                                    log.warn("Could not fetch profile for new user {}: {}", newUserId, e.getMessage());
                                                    return Mono.just(new KcUser(newUserId, username, firstName, lastName,
                                                            email, true, System.currentTimeMillis(), null, List.of(targetRole.toUpperCase())));
                                                })
                                ))
                                .doOnSuccess(user -> {
                                    AppEventMessage evt = new AppEventMessage();
                                    evt.setEventType("USER_CREATED");
                                    evt.setProducer("gatewayserver");
                                    AppEventMessage.Actor actor = new AppEventMessage.Actor();
                                    actor.setUserId(actorUserId);
                                    actor.setRoles(actorRoles);
                                    evt.setActor(actor);
                                    AppEventMessage.Target target = new AppEventMessage.Target();
                                    target.setType("USER");
                                    target.setId(newUserId);
                                    evt.setTarget(target);
                                    evt.setReason("Account created with role: " + targetRole);
                                    eventPublisher.publish("audit.user", evt);
                                })
                );
    }


    // ── Update roles ──────────────────────────────────────────────────────────

    public Mono<Void> updateAllowedRoles(String userId, Set<String> requested,
                                         String reason, String actorUserId, boolean actorIsSuperAdmin) {
        final Set<String> requestedFinal = (requested == null) ? Set.of() : requested;
        final String actor = (actorUserId != null && !actorUserId.isBlank()) ? actorUserId : "SYSTEM";

        // Enforce allowed roles based on actor
        Set<String> allowedSet = actorIsSuperAdmin ? ALLOWED_BY_SUPERADMIN : ALLOWED_BY_ADMIN;
        if (!allowedSet.containsAll(requestedFinal))
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You are not allowed to assign these roles: " + requestedFinal));

        Mono<Map<String, KcRole>> allowedRoleRepMono = kc.listRealmRoles()
                .map(all -> all.stream().filter(r -> ALLOWED.contains(r.name()))
                        .collect(Collectors.toMap(KcRole::name, r -> r)));

        Mono<Set<String>> currentAllowedMono = kc.getUserRealmRoles(userId)
                .map(current -> current.stream().map(KcRole::name)
                        .filter(ALLOWED::contains).collect(Collectors.toSet()));

        return Mono.zip(allowedRoleRepMono, currentAllowedMono)
                .flatMap(tuple -> {
                    Map<String, KcRole> allowedMap = tuple.getT1();
                    Set<String> currentAllowed = tuple.getT2();

                    Set<String> toAddNames = new HashSet<>(requestedFinal);
                    toAddNames.removeAll(currentAllowed);
                    Set<String> toRemoveNames = new HashSet<>(currentAllowed);
                    toRemoveNames.removeAll(requestedFinal);

                    List<KcRole> toAdd = toAddNames.stream().map(allowedMap::get).filter(Objects::nonNull).toList();
                    List<KcRole> toRemove = toRemoveNames.stream().map(allowedMap::get).filter(Objects::nonNull).toList();

                    AppEventMessage evt = new AppEventMessage();
                    evt.setEventType("ROLE_UPDATE");
                    evt.setProducer("gatewayserver");
                    AppEventMessage.Actor actorObj = new AppEventMessage.Actor();
                    actorObj.setUserId(actor);
                    evt.setActor(actorObj);
                    AppEventMessage.Target target = new AppEventMessage.Target();
                    target.setType("USER");
                    target.setId(userId);
                    evt.setTarget(target);
                    Map<String, Object> changes = new HashMap<>();
                    changes.put("oldRoles", currentAllowed.stream().sorted().toList());
                    changes.put("newRoles", requestedFinal.stream().sorted().toList());
                    evt.setChanges(changes);
                    evt.setReason(reason != null && !reason.isBlank() ? reason : "Roles updated");
                    eventPublisher.publish("audit.user", evt);

                    Mono<Void> addCall    = toAdd.isEmpty()    ? Mono.empty() : kc.addRealmRoles(userId, toAdd);
                    Mono<Void> removeCall = toRemove.isEmpty() ? Mono.empty() : kc.removeRealmRoles(userId, toRemove);
                    return addCall.then(removeCall);
                });
    }

    public List<String> allowedRoles() {
        return ALLOWED.stream().sorted().toList();
    }

    // ── Block user ────────────────────────────────────────────────────────────

    public Mono<Void> blockUser(String userId, String reason, String actorUserId, String authHeader) {
        String actor = (actorUserId != null && !actorUserId.isBlank()) ? actorUserId : "SYSTEM";

        return kc.setUserEnabled(userId, false)
                .then(cascadeBlockApplications(userId, authHeader))
                .doOnSuccess(v -> {
                    AppEventMessage event = new AppEventMessage();
                    event.setEventType("USER_BLOCK");
                    AppEventMessage.Actor actorObj = new AppEventMessage.Actor();
                    actorObj.setUserId(actor);
                    actorObj.setRoles(List.of("ADMIN"));
                    event.setActor(actorObj);
                    AppEventMessage.Target target = new AppEventMessage.Target();
                    target.setType("USER");
                    target.setId(userId);
                    event.setTarget(target);
                    event.setReason(reason != null && !reason.isBlank() ? reason : "Blocked by admin");
                    event.setProducer("gatewayserver");
                    eventPublisher.publish("audit.user", event);
                    eventPublisher.publish("notify.user", event);
                });
    }

    // ── Unblock user ──────────────────────────────────────────────────────────

    public Mono<Void> unblockUser(String userId, String reason, String actorUserId, String authHeader) {
        String actor = (actorUserId != null && !actorUserId.isBlank()) ? actorUserId : "SYSTEM";

        return kc.setUserEnabled(userId, true)
                .then(cascadeUnblockApplications(userId, authHeader))
                .doOnSuccess(v -> {
                    AppEventMessage event = new AppEventMessage();
                    event.setEventType("USER_UNBLOCK");
                    AppEventMessage.Actor actorObj = new AppEventMessage.Actor();
                    actorObj.setUserId(actor);
                    actorObj.setRoles(List.of("ADMIN"));
                    event.setActor(actorObj);
                    AppEventMessage.Target target = new AppEventMessage.Target();
                    target.setType("USER");
                    target.setId(userId);
                    event.setTarget(target);
                    event.setProducer("gatewayserver");
                    event.setReason(reason != null && !reason.isBlank() ? reason : "Unblocked by admin");
                    eventPublisher.publish("audit.user", event);
                    eventPublisher.publish("notify.user", event);
                });
    }

    // ── Dismiss signal ────────────────────────────────────────────────────────

    public Mono<Void> dismissCandidateSignal(String userId, String authHeader) {
        log.info("Calling dismiss: {}/api/applications/internal/dismiss/{}", applicationServiceUrl, userId);
        return webClient.post()
                .uri(applicationServiceUrl + "/api/applications/internal/dismiss/" + userId)
                .header("Authorization", authHeader)
                .retrieve()
                .bodyToMono(Void.class)
                .onErrorResume(e -> {
                    log.warn("dismissCandidateSignal failed: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    // ── Cascade helpers ───────────────────────────────────────────────────────

    private Mono<Void> cascadeBlockApplications(String userId, String authHeader) {
        return webClient.post()
                .uri(applicationServiceUrl + "/api/applications/internal/block/" + userId)
                .header("Authorization", authHeader)
                .retrieve()
                .bodyToMono(Void.class)
                .onErrorResume(e -> {
                    log.warn("cascadeBlockApplications failed: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> cascadeUnblockApplications(String userId, String authHeader) {
        return webClient.post()
                .uri(applicationServiceUrl + "/api/applications/internal/unblock/" + userId)
                .header("Authorization", authHeader)
                .retrieve()
                .bodyToMono(Void.class)
                .onErrorResume(e -> {
                    log.warn("cascadeUnblockApplications failed: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    public Mono<Long> countUsers(int first, int max, String search) {
        return kc.countUsers(search);
    }
}