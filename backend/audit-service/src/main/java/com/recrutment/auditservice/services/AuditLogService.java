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

    // ── Event type sets per role ──────────────────────────────────────────────

    /** All events — SUPERADMIN sees everything */
    private static final List<String> ALL_EVENTS = List.of(
            "APPLICATION_STATUS_UPDATE", "APPLICATION_WITHDRAWN",
            "JOB_UPDATED",
            "USER_BLOCK", "USER_UNBLOCK",
            "CANDIDATE_FLAGGED", "CANDIDATE_UNFLAGGED", "CANDIDATE_SIGNAL_DISMISSED",
            "ROLE_UPDATE"
    );

    /** ADMIN sees all except ROLE_UPDATE and SUPERADMIN actions */
    private static final List<String> ADMIN_EVENTS = List.of(
            "APPLICATION_STATUS_UPDATE", "APPLICATION_WITHDRAWN",
            "JOB_UPDATED",
            "USER_BLOCK", "USER_UNBLOCK",
            "CANDIDATE_FLAGGED", "CANDIDATE_UNFLAGGED", "CANDIDATE_SIGNAL_DISMISSED"
    );

    /** RECRUITER sees only recruitment events — no blocks, no dismiss, no roles */
    private static final List<String> RECRUITER_EVENTS = List.of(
            "APPLICATION_STATUS_UPDATE", "APPLICATION_WITHDRAWN",
            "JOB_UPDATED",
            "CANDIDATE_FLAGGED", "CANDIDATE_UNFLAGGED"
    );

    // ── Main log query — role-aware ───────────────────────────────────────────

    /**
     * @param callerRole  "SUPERADMIN" | "ADMIN" | "RECRUITER"
     */
    public Page<AuditLog> getLogs(String eventType, String producer, String targetId,
                                  String range, int page, int size, String callerRole) {

        // Used for SUPERADMIN (JPQL queries — Spring translates field names correctly)
        Pageable sortedPageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt"));

        // FIX: Used for ADMIN/RECRUITER (native SQL queries).
        // Native queries already have ORDER BY a.occurred_at DESC hardcoded.
        // Passing a sort here would send "occurredAt" (Java name) directly to SQL → crash.
        // So we pass an unsorted Pageable and let the SQL handle ordering.
        Pageable nativePageable = PageRequest.of(page, size);

        Instant from = resolveFrom(range);

        // targetId filter — use simple JPQL query
        if (targetId != null && eventType != null) {
            return repository.findByEventTypeAndTargetId(eventType, targetId, sortedPageable);
        }

        return switch (callerRole == null ? "SUPERADMIN" : callerRole.toUpperCase()) {
            case "SUPERADMIN" -> {
                // No restrictions — uses Spring Data JPQL (sort works fine)
                if (from != null) {
                    yield eventType != null
                            ? repository.findByEventTypeAndCreatedAtBetween(eventType, from, Instant.now(), sortedPageable)
                            : repository.findByCreatedAtBetween(from, Instant.now(), sortedPageable);
                }
                yield eventType != null
                        ? repository.findByEventType(eventType, sortedPageable)
                        : repository.findAll(sortedPageable);
            }
            case "ADMIN" -> {
                // FIX: use nativePageable — native query handles ORDER BY itself
                yield repository.findFilteredExcludeRole(eventType, ADMIN_EVENTS, "SUPERADMIN", from, nativePageable);
            }
            case "RECRUITER" -> {
                // FIX: use nativePageable — native query handles ORDER BY itself
                yield repository.findFilteredByEventTypes(eventType, RECRUITER_EVENTS, from, nativePageable);
            }
            default -> repository.findAll(sortedPageable);
        };
    }

    // ── Stats — role-aware ────────────────────────────────────────────────────

    public Map<String, Long> getStats(String range, String callerRole) {
        Instant from = resolveFrom(range);
        boolean isAdmin = "ADMIN".equalsIgnoreCase(callerRole);
        boolean isSuperAdmin = callerRole == null || "SUPERADMIN".equalsIgnoreCase(callerRole);

        if (from != null) {
            Instant to = Instant.now();
            if (isSuperAdmin) {
                return Map.of(
                        "total",               repository.countByCreatedAtBetween(from, to),
                        "applicationUpdates",  repository.countByEventTypeAndCreatedAtBetween("APPLICATION_STATUS_UPDATE", from, to),
                        "userBlocks",          repository.countByEventTypeAndCreatedAtBetween("USER_BLOCK", from, to),
                        "userUnblocks",        repository.countByEventTypeAndCreatedAtBetween("USER_UNBLOCK", from, to),
                        "jobUpdates",          repository.countByEventTypeAndCreatedAtBetween("JOB_UPDATED", from, to),
                        "candidateFlagged",    repository.countByEventTypeAndCreatedAtBetween("CANDIDATE_FLAGGED", from, to),
                        "candidateUnflagged",  repository.countByEventTypeAndCreatedAtBetween("CANDIDATE_UNFLAGGED", from, to)
                );
            } else if (isAdmin) {
                return Map.of(
                        "total",               repository.countExcludeRoleBetween("SUPERADMIN", from, to),
                        "applicationUpdates",  repository.countByEventTypeExcludeRoleBetween("APPLICATION_STATUS_UPDATE", "SUPERADMIN", from, to),
                        "userBlocks",          repository.countByEventTypeExcludeRoleBetween("USER_BLOCK", "SUPERADMIN", from, to),
                        "userUnblocks",        repository.countByEventTypeExcludeRoleBetween("USER_UNBLOCK", "SUPERADMIN", from, to),
                        "jobUpdates",          repository.countByEventTypeExcludeRoleBetween("JOB_UPDATED", "SUPERADMIN", from, to),
                        "candidateFlagged",    repository.countByEventTypeExcludeRoleBetween("CANDIDATE_FLAGGED", "SUPERADMIN", from, to),
                        "candidateUnflagged",  repository.countByEventTypeExcludeRoleBetween("CANDIDATE_UNFLAGGED", "SUPERADMIN", from, to)
                );
            } else {
                return Map.of(
                        "total",               repository.countByEventTypeAndCreatedAtBetween("APPLICATION_STATUS_UPDATE", from, to)
                                + repository.countByEventTypeAndCreatedAtBetween("JOB_UPDATED", from, to)
                                + repository.countByEventTypeAndCreatedAtBetween("CANDIDATE_FLAGGED", from, to),
                        "applicationUpdates",  repository.countByEventTypeAndCreatedAtBetween("APPLICATION_STATUS_UPDATE", from, to),
                        "userBlocks",          0L,
                        "userUnblocks",        0L,
                        "jobUpdates",          repository.countByEventTypeAndCreatedAtBetween("JOB_UPDATED", from, to),
                        "candidateFlagged",    repository.countByEventTypeAndCreatedAtBetween("CANDIDATE_FLAGGED", from, to),
                        "candidateUnflagged",  repository.countByEventTypeAndCreatedAtBetween("CANDIDATE_UNFLAGGED", from, to)
                );
            }
        }

        // No date range
        if (isSuperAdmin) {
            return Map.of(
                    "total",               repository.count(),
                    "applicationUpdates",  repository.countByEventType("APPLICATION_STATUS_UPDATE"),
                    "userBlocks",          repository.countByEventType("USER_BLOCK"),
                    "userUnblocks",        repository.countByEventType("USER_UNBLOCK"),
                    "jobUpdates",          repository.countByEventType("JOB_UPDATED"),
                    "candidateFlagged",    repository.countByEventType("CANDIDATE_FLAGGED"),
                    "candidateUnflagged",  repository.countByEventType("CANDIDATE_UNFLAGGED")
            );
        } else if (isAdmin) {
            return Map.of(
                    "total",               repository.countExcludeRole("SUPERADMIN"),
                    "applicationUpdates",  repository.countByEventTypeExcludeRole("APPLICATION_STATUS_UPDATE", "SUPERADMIN"),
                    "userBlocks",          repository.countByEventTypeExcludeRole("USER_BLOCK", "SUPERADMIN"),
                    "userUnblocks",        repository.countByEventTypeExcludeRole("USER_UNBLOCK", "SUPERADMIN"),
                    "jobUpdates",          repository.countByEventTypeExcludeRole("JOB_UPDATED", "SUPERADMIN"),
                    "candidateFlagged",    repository.countByEventTypeExcludeRole("CANDIDATE_FLAGGED", "SUPERADMIN"),
                    "candidateUnflagged",  repository.countByEventTypeExcludeRole("CANDIDATE_UNFLAGGED", "SUPERADMIN")
            );
        } else {
            return Map.of(
                    "total",               repository.countByEventType("APPLICATION_STATUS_UPDATE")
                            + repository.countByEventType("JOB_UPDATED")
                            + repository.countByEventType("CANDIDATE_FLAGGED"),
                    "applicationUpdates",  repository.countByEventType("APPLICATION_STATUS_UPDATE"),
                    "userBlocks",          0L,
                    "userUnblocks",        0L,
                    "jobUpdates",          repository.countByEventType("JOB_UPDATED"),
                    "candidateFlagged",    repository.countByEventType("CANDIDATE_FLAGGED"),
                    "candidateUnflagged",  repository.countByEventType("CANDIDATE_UNFLAGGED")
            );
        }
    }

    // ── Kept for backward compatibility ──────────────────────────────────────

    public Page<AuditLog> getLogs(String eventType, String producer, String targetId,
                                  String range, int page, int size) {
        return getLogs(eventType, producer, targetId, range, page, size, "SUPERADMIN");
    }

    public Map<String, Long> getStats(String range) {
        return getStats(range, "SUPERADMIN");
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

    public Page<AuditLog> getLogsByActor(String actorUserId, String eventType, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt"));
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

    public String getLastFlaggedBy(String candidateUserId) {
        Pageable pageable = PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "occurredAt"));
        Page<AuditLog> result = repository.findByEventTypeAndTargetId(
                "CANDIDATE_FLAGGED", candidateUserId, pageable);
        return result.getContent().isEmpty() ? null : result.getContent().get(0).getActorUserId();
    }

    public String getCandidateModerationStatus(String candidateUserId) {
        Pageable pageable = PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "occurredAt"));
        List<String> moderationEvents = List.of(
                "CANDIDATE_FLAGGED", "CANDIDATE_UNFLAGGED",
                "USER_BLOCK", "USER_UNBLOCK",
                "CANDIDATE_SIGNAL_DISMISSED"
        );
        Page<AuditLog> latest = repository.findByTargetIdAndEventTypeIn(
                candidateUserId, moderationEvents, pageable);
        if (latest.getContent().isEmpty()) return "CLEAR";
        String lastEvent = latest.getContent().get(0).getEventType();
        return switch (lastEvent) {
            case "CANDIDATE_FLAGGED" -> "FLAGGED";
            case "USER_BLOCK"        -> "BLOCKED";
            default                  -> "CLEAR";
        };
    }
}