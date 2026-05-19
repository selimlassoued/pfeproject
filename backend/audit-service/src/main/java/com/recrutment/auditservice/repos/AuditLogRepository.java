package com.recrutment.auditservice.repos;

import com.recrutment.auditservice.entities.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
    Page<AuditLog> findByActorUserIdAndEventTypeIn(String actorUserId, List<String> eventTypes, Pageable pageable);
    Page<AuditLog> findByActorUserIdAndEventType(String actorUserId, String eventType, Pageable pageable);

    Page<AuditLog> findByEventTypeIn(List<String> eventTypes, Pageable pageable);
    Page<AuditLog> findByEventTypeInAndCreatedAtBetween(List<String> eventTypes, Instant from, Instant to, Pageable pageable);
    Page<AuditLog> findByEventTypeAndTargetId(String eventType, String targetId, Pageable pageable);
    Page<AuditLog> findByTargetIdAndEventTypeIn(String targetId, List<String> eventTypes, Pageable pageable);

    // ── Role-aware log queries ────────────────────────────────────────────────

    /**
     * Filter by allowed event types + exclude logs from actors with a specific role.
     * Used by ADMIN to exclude SUPERADMIN actions.
     *
     * FIX: CAST(:from AS timestamp) tells PostgreSQL the type explicitly,
     *      preventing "could not determine data type of parameter $N" when :from is NULL.
     */
    @Query(
            value = "SELECT * FROM audit_logs a " +
                    "WHERE (:eventType IS NULL OR a.event_type = :eventType) " +
                    "AND a.event_type IN (:allowedTypes) " +
                    "AND (a.actor_roles IS NULL OR a.actor_roles NOT LIKE CONCAT('%', :excludeRole, '%')) " +
                    "AND (CAST(:from AS timestamp) IS NULL OR a.occurred_at >= CAST(:from AS timestamp)) " +
                    "ORDER BY a.occurred_at DESC",
            countQuery = "SELECT COUNT(*) FROM audit_logs a " +
                    "WHERE (:eventType IS NULL OR a.event_type = :eventType) " +
                    "AND a.event_type IN (:allowedTypes) " +
                    "AND (a.actor_roles IS NULL OR a.actor_roles NOT LIKE CONCAT('%', :excludeRole, '%')) " +
                    "AND (CAST(:from AS timestamp) IS NULL OR a.occurred_at >= CAST(:from AS timestamp))",
            nativeQuery = true
    )
    Page<AuditLog> findFilteredExcludeRole(
            @Param("eventType")    String eventType,
            @Param("allowedTypes") List<String> allowedTypes,
            @Param("excludeRole")  String excludeRole,
            @Param("from")         Instant from,
            Pageable pageable
    );

    /**
     * Filter by allowed event types only (no role exclusion).
     * Used by RECRUITER to see only recruitment-related events.
     *
     * FIX: CAST(:from AS timestamp) tells PostgreSQL the type explicitly,
     *      preventing "could not determine data type of parameter $N" when :from is NULL.
     */
    @Query(
            value = "SELECT * FROM audit_logs a " +
                    "WHERE (:eventType IS NULL OR a.event_type = :eventType) " +
                    "AND a.event_type IN (:allowedTypes) " +
                    "AND (CAST(:from AS timestamp) IS NULL OR a.occurred_at >= CAST(:from AS timestamp)) " +
                    "ORDER BY a.occurred_at DESC",
            countQuery = "SELECT COUNT(*) FROM audit_logs a " +
                    "WHERE (:eventType IS NULL OR a.event_type = :eventType) " +
                    "AND a.event_type IN (:allowedTypes) " +
                    "AND (CAST(:from AS timestamp) IS NULL OR a.occurred_at >= CAST(:from AS timestamp))",
            nativeQuery = true
    )
    Page<AuditLog> findFilteredByEventTypes(
            @Param("eventType")    String eventType,
            @Param("allowedTypes") List<String> allowedTypes,
            @Param("from")         Instant from,
            Pageable pageable
    );

    // ── Stats with role exclusion ─────────────────────────────────────────────

    @Query(value = "SELECT COUNT(*) FROM audit_logs a WHERE a.event_type = :type AND (a.actor_roles IS NULL OR a.actor_roles NOT LIKE CONCAT('%', :excludeRole, '%'))", nativeQuery = true)
    long countByEventTypeExcludeRole(@Param("type") String type, @Param("excludeRole") String excludeRole);

    @Query(value = "SELECT COUNT(*) FROM audit_logs a WHERE a.event_type = :type AND (a.actor_roles IS NULL OR a.actor_roles NOT LIKE CONCAT('%', :excludeRole, '%')) AND a.occurred_at BETWEEN :from AND :to", nativeQuery = true)
    long countByEventTypeExcludeRoleBetween(@Param("type") String type, @Param("excludeRole") String excludeRole, @Param("from") Instant from, @Param("to") Instant to);

    @Query(value = "SELECT COUNT(*) FROM audit_logs a WHERE (a.actor_roles IS NULL OR a.actor_roles NOT LIKE CONCAT('%', :excludeRole, '%'))", nativeQuery = true)
    long countExcludeRole(@Param("excludeRole") String excludeRole);

    @Query(value = "SELECT COUNT(*) FROM audit_logs a WHERE (a.actor_roles IS NULL OR a.actor_roles NOT LIKE CONCAT('%', :excludeRole, '%')) AND a.occurred_at BETWEEN :from AND :to", nativeQuery = true)
    long countExcludeRoleBetween(@Param("excludeRole") String excludeRole, @Param("from") Instant from, @Param("to") Instant to);
}