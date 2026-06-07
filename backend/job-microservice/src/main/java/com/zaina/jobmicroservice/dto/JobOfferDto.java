package com.zaina.jobmicroservice.dto;

import com.zaina.jobmicroservice.domain.enums.EmploymentType;
import com.zaina.jobmicroservice.domain.enums.JobStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobOfferDto {
    private UUID id;
    private String refNumber;

    @NotBlank
    @Size(max = 200)
    private String title;

    @Size(max = 10_000)
    private String description;

    @Size(max = 200)
    private String location;

    @Size(max = 50)
    private String workArrangement;

    @Size(max = 100)
    private String domain;

    @Min(value = 0, message = "minSalary cannot be negative")
    private Integer minSalary;

    @Min(value = 0, message = "maxSalary cannot be negative")
    private Integer maxSalary;

    @Positive(message = "openings must be at least 1")
    private Integer openings;

    @Min(value = 0)
    private Integer hiredCount;

    @NotNull
    private EmploymentType employmentType;

    private JobStatus jobStatus;

    private Double skillsWeight;
    private Double semanticWeight;
    private Double experienceWeight;
    private Double seniorityWeight;

    @Valid
    private List<JobRequirementDto> requirements;

    private Instant createdAt;
}