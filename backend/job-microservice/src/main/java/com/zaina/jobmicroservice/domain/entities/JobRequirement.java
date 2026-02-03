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

    private String description;
    private Double weight;
    private Integer minYears;
    private Integer maxYears;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_offer_id", nullable = false)
    @JsonBackReference
    private JobOffer jobOffer;
}
