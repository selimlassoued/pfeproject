package com.recrutment.auditservice.services;

import com.recrutment.auditservice.entities.AuditLog;
import com.recrutment.auditservice.repos.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository repository;

    public Page<AuditLog> getLogs(String eventType, String producer, String range, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Instant from = resolveFrom(range);

        if (from != null) {
            if (eventType != null) {
                return repository.findByEventTypeAndCreatedAtBetween(eventType, from, Instant.now(), pageable);
            }
            return repository.findByCreatedAtBetween(from, Instant.now(), pageable);
        }
        if (eventType != null) return repository.findByEventType(eventType, pageable);
        if (producer != null) return repository.findByProducer(producer, pageable);
        return repository.findAll(pageable);
    }

    public Map<String, Long> getStats(String range) {
        Instant from = resolveFrom(range);
        if (from != null) {
            Instant to = Instant.now();
            return Map.of(
                    "total",                repository.countByCreatedAtBetween(from, to),
                    "applicationUpdates",   repository.countByEventTypeAndCreatedAtBetween("APPLICATION_STATUS_UPDATE", from, to),
                    "userBlocks",           repository.countByEventTypeAndCreatedAtBetween("USER_BLOCK", from, to),
                    "userUnblocks",         repository.countByEventTypeAndCreatedAtBetween("USER_UNBLOCK", from, to),
                    "jobUpdates",           repository.countByEventTypeAndCreatedAtBetween("JOB_UPDATED", from, to)
            );
        }
        return Map.of(
                "total",                repository.count(),
                "applicationUpdates",   repository.countByEventType("APPLICATION_STATUS_UPDATE"),
                "userBlocks",           repository.countByEventType("USER_BLOCK"),
                "userUnblocks",         repository.countByEventType("USER_UNBLOCK"),
                "jobUpdates",           repository.countByEventType("JOB_UPDATED")
        );
    }

    private Instant resolveFrom(String range) {
        if (range == null) return null;
        return switch (range) {
            case "week"  -> Instant.now().minus(7,   ChronoUnit.DAYS);
            case "month" -> Instant.now().minus(30,  ChronoUnit.DAYS);
            case "year"  -> Instant.now().minus(365, ChronoUnit.DAYS);
            default      -> null;
        };
    }

    // ── updated: eventType param added ──
    public Page<AuditLog> getLogsByActor(String actorUserId, String eventType, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        if (eventType != null) {
            return repository.findByActorUserIdAndEventType(actorUserId, eventType, pageable);
        }
        return repository.findByActorUserId(actorUserId, pageable);
    }

    public Map<String, Long> getRecruiterActivity() {
        List<String> types = List.of("JOB_UPDATED", "APPLICATION_STATUS_UPDATE");
        return repository.findByEventTypeIn(types, Pageable.unpaged())
                .getContent()
                .stream()
                .collect(Collectors.groupingBy(AuditLog::getActorUserId, Collectors.counting()));
    }
}