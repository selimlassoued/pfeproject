package com.recrutment.auditservice.restControllers;

import com.recrutment.auditservice.entities.AuditLog;
import com.recrutment.auditservice.services.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService service;

    /**
     * Main logs endpoint — role-aware.
     * @param callerRole  "SUPERADMIN" | "ADMIN" | "RECRUITER" — passed by Angular in header or param
     */
    @GetMapping("/logs")
    public Page<AuditLog> getLogs(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String producer,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) String range,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false, defaultValue = "SUPERADMIN") String callerRole) {
        return service.getLogs(eventType, producer, targetId, range, page, size, callerRole);
    }

    /**
     * Stats endpoint — role-aware.
     */
    @GetMapping("/stats")
    public Map<String, Long> getStats(
            @RequestParam(required = false) String range,
            @RequestParam(required = false, defaultValue = "SUPERADMIN") String callerRole) {
        return service.getStats(range, callerRole);
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