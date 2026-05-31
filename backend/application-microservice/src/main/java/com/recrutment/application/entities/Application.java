package com.recrutment.application.entities;

import com.recrutment.application.enums.ApplicationStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Application {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID applicationId;

    private UUID jobId;

    // from Keycloak JWT (sub)
    private String candidateUserId;

    private String githubUrl;

    @Enumerated(EnumType.STRING)
    private ApplicationStatus status;

    private Instant appliedAt;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "cv_file", nullable = false)
    private byte[] cvFile;

    @Column(nullable = false)
    private String cvFileName;

    @Column(nullable = false)
    private String cvContentType;

    // ── Moderation ────────────────────────────────────────────────────────────
    // Only previousStatus is stored here — it's functional state needed to
    // restore the application after unblock/dismiss.
    // flaggedBy, flagReason, flaggedAt are in the audit service (AuditLog).
    @Enumerated(EnumType.STRING)
    private ApplicationStatus previousStatus;

    // Explains an automatic WITHDRAWN, e.g. when the candidate was hired for
    // another position so this pipeline was closed on their behalf.
    @Column(columnDefinition = "TEXT")
    private String withdrawalReason;
}