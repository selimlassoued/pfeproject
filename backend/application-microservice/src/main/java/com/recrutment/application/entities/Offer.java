package com.recrutment.application.entities;

import com.recrutment.application.enums.ContractType;
import com.recrutment.application.enums.OfferStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Job offer extended to a candidate after the interview phase. The "current
 * terms" (salary / startDate / contractType) live directly on this row and are
 * mutated as the negotiation progresses — every change snapshots a new
 * {@link OfferRevision} so the full back-and-forth is preserved.
 *
 * On {@code ACCEPTED} the parent Application is moved to HIRED.
 */
@Entity
@Table(name = "application_offers")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Offer {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false)
    private UUID applicationId;

    @Column(nullable = false)
    private UUID jobId;

    @Column(nullable = false)
    private UUID recruiterId;

    @Column(nullable = false)
    private String candidateUserId;

    // ── Current terms (mutated by revisions) ────────────────────────────────
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal salary;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "TND";

    @Column(nullable = false)
    private LocalDate startDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContractType contractType;

    /** Recruiter's accompanying note shown on initial send (immutable). */
    @Column(columnDefinition = "TEXT")
    private String message;

    // ── Status ──────────────────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private OfferStatus status = OfferStatus.SENT;

    @Column(nullable = false)
    private Instant expiresAt;

    // ── Audit timestamps ────────────────────────────────────────────────────
    @CreationTimestamp
    @Column(updatable = false, nullable = false)
    private Instant createdAt;

    private Instant respondedAt;
}
