package com.recrutment.auditservice.repos;

import com.recrutment.auditservice.entities.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    Page<AuditLog> findByEventType(String eventType, Pageable pageable);
    Page<AuditLog> findByProducer(String producer, Pageable pageable);
    long countByEventType(String eventType);
    Page<AuditLog> findByCreatedAtBetween(Instant from, Instant to, Pageable pageable);
    long countByCreatedAtBetween(Instant from, Instant to);
    long countByEventTypeAndCreatedAtBetween(String eventType, Instant from, Instant to);
    Page<AuditLog> findByEventTypeAndCreatedAtBetween(String eventType, Instant from, Instant to, Pageable pageable);
    List<AuditLog> findByActorUserId(String actorUserId);
    Page<AuditLog> findByActorUserId(String actorUserId, Pageable pageable);
    List<AuditLog> findByActorUserIdAndEventTypeIn(String actorUserId, List<String> eventTypes);

    // for recruiter activity grouping
    Page<AuditLog> findByEventTypeIn(List<String> eventTypes, Pageable pageable);
    Page<AuditLog> findByActorUserIdAndEventType(String actorUserId, String eventType, Pageable pageable);

}
