package com.recrutment.auditservice.restControllers;

import com.recrutment.auditservice.entities.AuditLog;
import com.recrutment.auditservice.services.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService service;

    /** Roles the gateway is allowed to stamp on X-Actor-Roles. Anything
     *  else means a misconfigured caller and we reject. */
    private static final Set<String> ALLOWED_ROLES =
            Set.of("SUPERADMIN", "ADMIN", "RECRUITER");

    /**
     * Main logs endpoint - role-aware.
     *
     * The caller's role is taken from the X-Actor-Roles header that the
     * gateway stamps after verifying the JWT. The previous version read
     * it from a query parameter that defaulted to "SUPERADMIN", which
     * let a RECRUITER call /api/audit/logs?callerRole=SUPERADMIN and
     * see everyone's audit trail.
     */
    @GetMapping("/logs")
    public Page<AuditLog> getLogs(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String producer,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) String range,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "X-Actor-Roles", required = false) String actorRoles) {
        return service.getLogs(eventType, producer, targetId, range, page, size, requireRole(actorRoles));
    }

    /** Stats endpoint - role-aware (same trusted-header rule as /logs). */
    @GetMapping("/stats")
    public Map<String, Long> getStats(
            @RequestParam(required = false) String range,
            @RequestHeader(value = "X-Actor-Roles", required = false) String actorRoles) {
        return service.getStats(range, requireRole(actorRoles));
    }

    private static String requireRole(String headerValue) {
        if (headerValue == null || !ALLOWED_ROLES.contains(headerValue)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing or invalid actor role");
        }
        return headerValue;
    }

    @GetMapping("/logs/actor/{actorId}")
    public Page<AuditLog> getByActor(
            @PathVariable String actorId,
            @RequestParam(required = false) String eventType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.getLogsByActor(actorId, eventType, page, size);
    }

    @GetMapping("/recruiter-activity")
    public Map<String, Long> recruiterActivity() {
        return service.getRecruiterActivity();
    }

    @GetMapping("/candidate/{candidateUserId}/flagged-by")
    public Map<String, String> getLastFlaggedBy(@PathVariable String candidateUserId) {
        String flaggedBy = service.getLastFlaggedBy(candidateUserId);
        return Map.of("flaggedBy", flaggedBy != null ? flaggedBy : "");
    }

    @GetMapping("/candidate/{candidateUserId}/moderation-status")
    public Map<String, String> getCandidateModerationStatus(@PathVariable String candidateUserId) {
        String status = service.getCandidateModerationStatus(candidateUserId);
        return Map.of("status", status);
    }
}