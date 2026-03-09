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

    @GetMapping("/logs")
    public Page<AuditLog> getLogs(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String producer,
            @RequestParam(required = false) String range,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.getLogs(eventType, producer, range, page, size);
    }

    @GetMapping("/stats")
    public Map<String, Long> getStats(@RequestParam(required = false) String range) {
        return service.getStats(range);
    }

    @GetMapping("/logs/actor/{actorId}")
    public Page<AuditLog> getByActor(
            @PathVariable String actorId,
            @RequestParam(required = false) String eventType,   // ← ADDED
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.getLogsByActor(actorId, eventType, page, size);
    }

    @GetMapping("/recruiter-activity")
    public Map<String, Long> recruiterActivity() {
        return service.getRecruiterActivity();
    }
}