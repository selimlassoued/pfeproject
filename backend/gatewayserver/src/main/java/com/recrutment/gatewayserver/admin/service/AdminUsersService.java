package com.recrutment.gatewayserver.admin.service;

import com.recrutment.gatewayserver.admin.KeycloakAdminClient;
import com.recrutment.gatewayserver.admin.dto.KcDtos.KcRole;
import com.recrutment.gatewayserver.admin.dto.KcDtos.KcUser;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class AdminUsersService {

    private final KeycloakAdminClient kc;
    private static final Set<String> ALLOWED = Set.of("CANDIDATE", "RECRUITER", "ADMIN");

    public AdminUsersService(KeycloakAdminClient kc) {
        this.kc = kc;
    }

    // ✅ list users + enrich each one with allowed roles
    public Mono<List<KcUser>> listUsers(int first, int max, String search) {
        return kc.listUsers(first, max, search)
                .flatMap(users ->
                        Flux.fromIterable(users)
                                // sequential to avoid flooding Keycloak with parallel calls
                                .flatMapSequential(u -> getAllowedRolesSafe(u.id())
                                        .map(roles -> copyWithRoles(u, roles)))
                                .collectList()
                );
    }

    // ✅ get profile + enrich with roles
    public Mono<KcUser> getProfile(String userId) {
        return Mono.zip(
                        kc.getUser(userId),
                        getAllowedRolesSafe(userId)
                )
                .map(tuple -> copyWithRoles(tuple.getT1(), tuple.getT2()));
    }

    // Your original method (kept)
    public Mono<List<String>> getAllowedRoles(String userId) {
        return kc.getUserRealmRoles(userId)
                .map(roles -> roles.stream()
                        .map(KcRole::name)
                        .filter(ALLOWED::contains)
                        .distinct()
                        .sorted()
                        .toList());
    }

    // ✅ safer: if Keycloak call fails for one user, don't break whole list
    private Mono<List<String>> getAllowedRolesSafe(String userId) {
        return getAllowedRoles(userId)
                .onErrorReturn(List.of());
    }

    // ✅ helper to build a new KcUser with roles filled
    private KcUser copyWithRoles(KcUser u, List<String> roles) {
        return new KcUser(
                u.id(),
                u.username(),
                u.firstName(),
                u.lastName(),
                u.email(),
                u.enabled(),
                u.createdTimestamp(),
                u.attributes(),
                roles
        );
    }

    public Mono<Void> updateAllowedRoles(String userId, Set<String> requested) {

        final Set<String> requestedFinal = (requested == null) ? Set.of() : requested;

        if (!ALLOWED.containsAll(requestedFinal)) {
            return Mono.error(new IllegalArgumentException("Only allowed roles: " + ALLOWED));
        }

        Mono<Map<String, KcRole>> allowedRoleRepMono =
                kc.listRealmRoles()
                        .map(all -> all.stream()
                                .filter(r -> ALLOWED.contains(r.name()))
                                .collect(Collectors.toMap(KcRole::name, r -> r)));

        Mono<Set<String>> currentAllowedMono =
                kc.getUserRealmRoles(userId)
                        .map(current -> current.stream()
                                .map(KcRole::name)
                                .filter(ALLOWED::contains)
                                .collect(Collectors.toSet()));

        return Mono.zip(allowedRoleRepMono, currentAllowedMono)
                .flatMap(tuple -> {
                    Map<String, KcRole> allowedMap = tuple.getT1();
                    Set<String> currentAllowed = tuple.getT2();

                    Set<String> toAddNames = new HashSet<>(requestedFinal);
                    toAddNames.removeAll(currentAllowed);

                    Set<String> toRemoveNames = new HashSet<>(currentAllowed);
                    toRemoveNames.removeAll(requestedFinal);

                    List<KcRole> toAdd = toAddNames.stream()
                            .map(allowedMap::get)
                            .filter(Objects::nonNull)
                            .toList();

                    List<KcRole> toRemove = toRemoveNames.stream()
                            .map(allowedMap::get)
                            .filter(Objects::nonNull)
                            .toList();

                    Mono<Void> addCall = toAdd.isEmpty() ? Mono.empty() : kc.addRealmRoles(userId, toAdd);
                    Mono<Void> removeCall = toRemove.isEmpty() ? Mono.empty() : kc.removeRealmRoles(userId, toRemove);

                    return addCall.then(removeCall);
                });
    }

    public List<String> allowedRoles() {
        return ALLOWED.stream().sorted().toList();
    }
    public Mono<Void> blockUser(String userId) {
        return kc.setUserEnabled(userId, false);
    }

    public Mono<Void> unblockUser(String userId) {
        return kc.setUserEnabled(userId, true);
    }

    public Mono<Void> deleteUser(String userId) {
        return kc.deleteUser(userId);
    }
    public Mono<Long> countUsers(int first, int max, String search) {
        return kc.countUsers(search);
    }
}
