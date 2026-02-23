package com.recrutment.gatewayserver.admin.restControllers;

import com.recrutment.gatewayserver.admin.dto.PageResponse;
import com.recrutment.gatewayserver.admin.service.AdminUsersService;
import com.recrutment.gatewayserver.admin.dto.KcDtos.KcUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.Authentication;
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
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<List<KcUser>> listUsers(
            @RequestParam(defaultValue = "0") int first,
            @RequestParam(defaultValue = "20") int max,
            @RequestParam(required = false) String search
    ) {
        return service.listUsers(first, max, search);
    }

    @GetMapping("/users/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<KcUser> getUserProfile(@PathVariable String id) {
        return service.getProfile(id);
    }

    public record BlockUnblockRequest(String reason) {}

    private static Mono<String> getActorUserId() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(a -> a != null && a.getPrincipal() instanceof Jwt)
                .map(a -> ((Jwt) a.getPrincipal()).getSubject())
                .defaultIfEmpty("SYSTEM");
    }

    // ✅ block
    @PutMapping("/users/{id}/block")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<Void> blockUser(@PathVariable String id, @RequestBody(required = false) BlockUnblockRequest request) {
        String reason = request != null && request.reason() != null ? request.reason() : "Blocked by admin";
        return getActorUserId().flatMap(actorId -> service.blockUser(id, reason, actorId));
    }

    // ✅ unblock
    @PutMapping("/users/{id}/unblock")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<Void> unblockUser(@PathVariable String id, @RequestBody(required = false) BlockUnblockRequest request) {
        String reason = request != null && request.reason() != null ? request.reason() : "Unblocked by admin";
        return getActorUserId().flatMap(actorId -> service.unblockUser(id, reason, actorId));
    }
    public record UpdateRolesRequest(List<String> roles, String reason) {}

    // ✅ delete
    @DeleteMapping("/users/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<Void> deleteUser(@PathVariable String id) {
        return service.deleteUser(id);
    }

    @GetMapping("/users/{id}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<Map<String, List<String>>> getUserRoles(@PathVariable String id) {
        return service.getAllowedRoles(id).map(r -> Map.of("roles", r));
    }

    @PutMapping("/users/{id}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<Void> updateUserRoles(@PathVariable String id, @RequestBody UpdateRolesRequest req) {
        Set<String> requested = new HashSet<>(req.roles() == null ? List.of() : req.roles());
        String reason = req.reason() != null ? req.reason() : "Roles updated by admin";
        return getActorUserId().flatMap(actorId -> service.updateAllowedRoles(id, requested, reason, actorId));
    }

    @GetMapping("/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public List<String> allowedRoles() {
        return service.allowedRoles();
    }

    @GetMapping("/users/paged")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<PageResponse<KcUser>> listUsersPaged(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search
    ) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);

        int first = safePage * safeSize;

        Mono<List<KcUser>> usersMono = service.listUsers(first, safeSize, search);
        Mono<Long> countMono = service.countUsers(first, safeSize, search);

        return Mono.zip(usersMono, countMono)
                .map(t -> {
                    List<KcUser> content = t.getT1();
                    long total = t.getT2();
                    int totalPages = (int) Math.ceil(total / (double) safeSize);
                    return new PageResponse<>(content, safePage, safeSize, total, totalPages);
                });
    }
}
