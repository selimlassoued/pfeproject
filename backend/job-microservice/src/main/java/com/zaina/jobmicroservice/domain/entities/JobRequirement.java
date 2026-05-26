package com.zaina.jobmicroservice.domain.entities;
import com.fasterxml.jackson.annotation.JsonBackReference;
import com.zaina.jobmicroservice.domain.enums.RequirementCategory;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
public class JobRequirement {
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    private RequirementCategory category;

    // Free-form requirement text — TEXT so recruiters can write long
    // OR/AND skill chains without tripping varchar(255).
    @Column(columnDefinition = "TEXT")
    private String description;
    private Double weight;
    private Integer minYears;
    private Integer maxYears;

    // Structured skill level — BASIC / INTERMEDIATE / ADVANCED (replaces parsing "Basic knowledge of…")
    private String skillLevel;

    // Structured education fields — replaces parsing "Bachelor in CS student or graduate"
    private String degreeLevel;      // ANY / BAC / BTS_DUT / LICENCE_BACHELOR / MASTER / PHD
    private String enrollmentType;   // STUDENT / GRADUATE / BOTH

    // Structured language level — CEFR scale
    private String languageLevel;    // A1 / A2 / B1 / B2 / C1 / C2

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_offer_id", nullable = false)
    @JsonBackReference
    private JobOffer jobOffer;
}
