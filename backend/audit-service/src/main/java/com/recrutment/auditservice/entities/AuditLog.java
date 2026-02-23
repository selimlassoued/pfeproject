package com.recrutment.auditservice.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;   // ID coming from the published event

    @Column(nullable = false)
    private String eventType;   // USER_BLOCKED, JOB_UPDATED, etc.

    @Column(nullable = false)
    private Instant occurredAt; // When event happened

    @Column(nullable = false)
    private String producer;    // gatewayserver, job-microservice...

    @Column(nullable = false)
    private String actorUserId; // Who performed the action

    private String actorRoles;  // Stored as JSON string or comma-separated

    @Column(nullable = false)
    private String targetType;  // USER, JOB, APPLICATION

    @Column(nullable = false)
    private String targetId;

    @Column(length = 1000)
    private String reason;      // Optional reason (block, etc.)

    @Column(columnDefinition = "TEXT")
    private String changes;     // JSON string (old/new diff)

    /**
     * Correlation id allowing to group related events across services.
     * Optional: if not provided by publisher it can be null.
     */
    private String correlationId;

    @Column(nullable = false)
    private Instant createdAt;  // When saved in audit DB
}
