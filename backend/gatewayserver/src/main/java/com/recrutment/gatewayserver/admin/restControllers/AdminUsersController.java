package com.recrutment.gatewayserver.admin.restControllers;

import com.recrutment.gatewayserver.admin.dto.PageResponse;
import com.recrutment.gatewayserver.admin.service.AdminUsersService;
import com.recrutment.gatewayserver.admin.dto.KcDtos.KcUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class AdminUsersController {

    private final AdminUsersService service;

    public AdminUsersController(AdminUsersService service) {
        this.service = service;
    }

    @GetMapping("/users")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public Mono<List<KcUser>> listUsers(
            @RequestParam(defaultValue = "0") int first,
            @RequestParam(defaultValue = "20") int max,
            @RequestParam(required = false) String search
    ) {
        return service.listUsers(first, max, search);
    }

    @GetMapping("/users/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN', 'RECRUITER')")
    public Mono<KcUser> getUserProfile(@PathVariable String id) {
        return service.getProfile(id);
    }

    @GetMapping("/users/paged")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public Mono<PageResponse<KcUser>> listUsersPaged(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search
    ) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);
        int first = safePage * safeSize;

        return Mono.zip(service.listUsers(first, safeSize, search), service.countUsers(first, safeSize, search))
                .map(t -> {
                    long total = t.getT2();
                    int totalPages = (int) Math.ceil(total / (double) safeSize);
                    return new PageResponse<>(t.getT1(), safePage, safeSize, total, totalPages);
                });
    }

    // ── Create user — ADMIN creates RECRUITER, SUPERADMIN creates ADMIN/RECRUITER ──

    public record CreateUserRequest(
            @NotBlank @Size(max = 80)  String firstName,
            @NotBlank @Size(max = 80)  String lastName,
            @NotBlank @Email @Size(max = 120) String email,
            @NotBlank @Pattern(regexp = "RECRUITER|ADMIN",
                    message = "role must be RECRUITER or ADMIN")
            String role
    ) {}

    @PostMapping("/users")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public Mono<KcUser> createUser(@Valid @RequestBody CreateUserRequest req) {
        return getActorInfo().flatMap(info ->
                service.createUser(
                        req.firstName(), req.lastName(), req.email(),
                        req.role(), info.userId(), info.roles()
                )
        );
    }


    // ── Block / Unblock ───────────────────────────────────────────────────────

    public record BlockUnblockRequest(@Size(max = 500) String reason) {}

    @PutMapping("/users/{id}/block")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public Mono<Void> blockUser(
            @PathVariable String id,
            @Valid @RequestBody(required = false) BlockUnblockRequest request,
            @RequestHeader("Authorization") String authHeader) {
        String reason = request != null && request.reason() != null ? request.reason() : "Blocked by admin";
        return getActorUserId().flatMap(actorId -> service.blockUser(id, reason, actorId, authHeader));
    }

    @PutMapping("/users/{id}/unblock")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public Mono<Void> unblockUser(
            @PathVariable String id,
            @Valid @RequestBody(required = false) BlockUnblockRequest request,
            @RequestHeader("Authorization") String authHeader) {
        String reason = request != null && request.reason() != null ? request.reason() : "Unblocked by admin";
        return getActorUserId().flatMap(actorId -> service.unblockUser(id, reason, actorId, authHeader));
    }

    @PutMapping("/users/{id}/dismiss-signal")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public Mono<Void> dismissSignal(
            @PathVariable String id,
            @RequestHeader("Authorization") String authHeader) {
        return service.dismissCandidateSignal(id, authHeader);
    }

    // ── Roles ─────────────────────────────────────────────────────────────────

    public record UpdateRolesRequest(
            @NotNull List<@NotBlank String> roles,
            @Size(max = 500) String reason
    ) {}

    // ✅ delete
//    @DeleteMapping("/users/{id}")
//    @PreAuthorize("hasRole('ADMIN')")
//    public Mono<Void> deleteUser(@PathVariable String id) {
//        return service.deleteUser(id);
//    }

    @GetMapping("/users/{id}/roles")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public Mono<Map<String, List<String>>> getUserRoles(@PathVariable String id) {
        return service.getAllowedRoles(id).map(r -> Map.of("roles", r));
    }

    @PutMapping("/users/{id}/roles")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public Mono<Void> updateUserRoles(@PathVariable String id, @Valid @RequestBody UpdateRolesRequest req) {
        Set<String> requested = new HashSet<>(req.roles() == null ? List.of() : req.roles());
        String reason = req.reason() != null ? req.reason() : "Roles updated";
        return getActorInfo().flatMap(info ->
                service.updateAllowedRoles(id, requested, reason, info.userId(),
                        info.roles().stream().anyMatch(r -> r.equalsIgnoreCase("SUPERADMIN")))
        );
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public List<String> allowedRoles() {
        return service.allowedRoles();
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    @GetMapping("/internal/users/{id}/email")
    public Mono<Map<String, String>> getUserEmail(@PathVariable String id) {
        return service.getProfile(id)
                .map(user -> Map.of(
                        "email", user.email() != null ? user.email() : "",
                        "firstName", user.firstName() != null ? user.firstName() : "",
                        "lastName", user.lastName() != null ? user.lastName() : ""
                ));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Mono<String> getActorUserId() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(a -> a != null && a.getPrincipal() instanceof Jwt)
                .map(a -> ((Jwt) a.getPrincipal()).getSubject())
                .defaultIfEmpty("SYSTEM");
    }

    private record ActorInfo(String userId, List<String> roles) {}

    private static Mono<ActorInfo> getActorInfo() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(a -> a != null && a.getPrincipal() instanceof Jwt)
                .map(a -> {
                    Jwt jwt = (Jwt) a.getPrincipal();
                    String userId = jwt.getSubject();
                    // Extract realm roles from JWT
                    Map<String, Object> realmAccess = jwt.getClaim("realm_access");
                    List<String> roles = List.of();
                    if (realmAccess != null && realmAccess.get("roles") instanceof List<?> r) {
                        roles = r.stream().map(Object::toString).toList();
                    }
                    return new ActorInfo(userId, roles);
                })
                .defaultIfEmpty(new ActorInfo("SYSTEM", List.of()));
    }
}