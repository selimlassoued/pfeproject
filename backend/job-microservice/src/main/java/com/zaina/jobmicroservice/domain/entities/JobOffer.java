package com.zaina.jobmicroservice.domain.entities;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.zaina.jobmicroservice.domain.enums.EmploymentType;
import com.zaina.jobmicroservice.domain.enums.JobStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
public class JobOffer {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(unique = true, length = 20)
    private String refNumber;   // set once after first save — never changed after that

    private String title;

    // Free-form job description — TEXT instead of default varchar(255) so
    // recruiters can write proper multi-paragraph postings without hitting
    // the column-length limit at insert time.
    @Column(columnDefinition = "TEXT")
    private String description;

    private String location;
    private String workArrangement; // REMOTE / HYBRID / ON_SITE

    // Business domain the job belongs to — feeds the candidate-side chip-grid
    // filter so an Insurance candidate doesn't have to scroll through Kafka
    // and Spring Boot to find Risk-Analysis skills. One of the same values
    // candidates pick on their Preferences page (SOFTWARE_ENGINEERING,
    // FINANCE_BANKING, INSURANCE, PROJECT_MANAGEMENT, QUALITY_ASSURANCE,
    // BUSINESS_ANALYSIS). Nullable so legacy jobs created before this field
    // existed still load.
    private String domain;

    // When the job was first persisted. Drives the "First seen" timestamp on
    // every skill the catalog extracts from this job — which in turn drives
    // the candidate-side NEW chip badge ("added since your last visit").
    // Auto-set by Hibernate on insert, never updated afterwards.
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
    private Integer minSalary;
    private Integer maxSalary;

    // ATS quota
    @Column(nullable = false)
    @Builder.Default
    private Integer openings = 1;     // number of positions

    @Column(nullable = false)
    @Builder.Default
    private Integer hiredCount = 0;   // incremented when a candidate is HIRED

    @Enumerated(EnumType.STRING)
    private EmploymentType employmentType;

    @Enumerated(EnumType.STRING)
    private JobStatus jobStatus;

    // ── Recruiter-configurable scoring weights ────────────────────────────────
    // Nullable to support existing rows — service falls back to defaults when null.
    @Builder.Default private Double skillsWeight     = 0.40;
    @Builder.Default private Double semanticWeight   = 0.35;
    @Builder.Default private Double experienceWeight = 0.15;
    @Builder.Default private Double seniorityWeight  = 0.10;

    @OneToMany(mappedBy = "jobOffer", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @JsonManagedReference
    private List<JobRequirement> requirements = new ArrayList<>();

    public void addRequirement(JobRequirement req) {
        requirements.add(req);
        req.setJobOffer(this);
    }

    public void removeRequirement(JobRequirement req) {
        requirements.remove(req);
        req.setJobOffer(null);
    }
}