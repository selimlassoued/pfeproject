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

    private String skillLevel;

    private String degreeLevel;
    private String enrollmentType;   // STUDENT / GRADUATE / BOTH
    private String institute;        // EDUCATION: name of the school/university (optional)

    // CERTIFICATION-specific fields ------------------------------------------
    // Vendor / organization that issues the cert (AWS, Microsoft, Cisco, ...).
    // When set, the matcher only counts certs whose text mentions this org.
    // Special value "OTHER" pairs with customIssuingOrg for free-text orgs.
    private String issuingOrg;
    // Free-text org name used when issuingOrg = "OTHER" (e.g. "MongoDB University",
    // "SAP", "ServiceNow"). The matcher uses this string as the keyword filter.
    private String customIssuingOrg;
    // When true, expired certs (cert_year + validityYears < current year) no
    // longer count as matched. Used for vendor certs that have a hard lifetime
    // (AWS = 3 yrs, Azure ~1 yr, Cisco CCNA = 3 yrs, PMP = 3 yrs, ...).
    private Boolean requireCurrent;
    // How many years the cert remains valid. Recruiter picks per the cert's
    // official policy. Only used when requireCurrent = true.
    private Integer validityYears;

    // Hard knockout flag. When true, a candidate that fails this requirement
    // is auto-flagged (visually demoted) regardless of overall score.
    // Stored as nullable Boolean so legacy rows default to "not must-have".
    private Boolean mustHave;

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
