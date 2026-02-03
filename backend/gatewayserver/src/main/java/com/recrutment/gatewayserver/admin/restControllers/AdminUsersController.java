package com.recrutment.gatewayserver.admin.restControllers;

import com.recrutment.gatewayserver.admin.service.AdminUsersService;
import com.recrutment.gatewayserver.admin.dto.KcDtos.KcUser;
import org.springframework.security.access.prepost.PreAuthorize;
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

    // ✅ block
    @PutMapping("/users/{id}/block")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<Void> blockUser(@PathVariable String id) {
        return service.blockUser(id);
    }

    // ✅ unblock
    @PutMapping("/users/{id}/unblock")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<Void> unblockUser(@PathVariable String id) {
        return service.unblockUser(id);
    }

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
        return service.updateAllowedRoles(id, requested);
    }

    @GetMapping("/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public List<String> allowedRoles() {
        return service.allowedRoles();
    }

    public record UpdateRolesRequest(List<String> roles) {}
}
