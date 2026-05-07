package com.zaina.jobmicroservice.domain.entities;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.zaina.jobmicroservice.domain.enums.EmploymentType;
import com.zaina.jobmicroservice.domain.enums.JobStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

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
    private String description;
    private String location;
    private String workArrangement; // REMOTE / HYBRID / ON_SITE
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