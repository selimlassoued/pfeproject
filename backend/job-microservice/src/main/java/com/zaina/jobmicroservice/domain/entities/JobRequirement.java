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

    private String skillLevel;

    private String degreeLevel;      
    private String enrollmentType;   // STUDENT / GRADUATE / BOTH

    // Structured language level — CEFR scale
    private String languageLevel;    // A1 / A2 / B1 / B2 / C1 / C2

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_offer_id", nullable = false)
    @JsonBackReference
    private JobOffer jobOffer;

    // Cached nomic-embed-text embedding for this single requirement's text.
    // Written by cv-parser-service via PUT after computing the vector during
    // the first match that needs it. Used to skip Ollama on every subsequent
    // applicant to the same job.
    //
    // Same insertable=false, updatable=false trick as JobOffer.embedding:
    // JPA's generic INSERT/UPDATE binds Strings as varchar and Postgres won't
    // auto-cast varchar to vector. The dedicated native UPDATE in
    // JobRequirementRepo casts explicitly.
    @Column(name = "embedding", columnDefinition = "vector(768)", insertable = false, updatable = false)
    private String embedding;

    @Column(name = "embedding_model", length = 64, insertable = false, updatable = false)
    private String embeddingModel;
}
