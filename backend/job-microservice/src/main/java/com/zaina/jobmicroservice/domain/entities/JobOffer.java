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
    private String title;
    private String description;
    private String location;
    private Integer minSalary;
    private Integer maxSalary;

    @Enumerated(EnumType.STRING)
    private EmploymentType employmentType;

    @Enumerated(EnumType.STRING)
    private JobStatus jobStatus;

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
