package com.recrutment.application.entities;

import com.recrutment.application.enums.ContractType;
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
 * Immutable snapshot of one round of the offer negotiation. The recruiter and
 * candidate each post revisions until one side accepts or the offer expires.
 * The {@link Offer} entity holds the "current" terms (mirror of the latest
 * revision); this table is the full history.
 */
@Entity
@Table(name = "application_offer_revisions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OfferRevision {

    public enum ProposedBy { RECRUITER, CANDIDATE }

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false)
    private UUID offerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProposedBy proposedBy;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal salary;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private LocalDate startDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContractType contractType;

    @Column(columnDefinition = "TEXT")
    private String message;

    @CreationTimestamp
    @Column(updatable = false, nullable = false)
    private Instant createdAt;
}
