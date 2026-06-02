package com.zaina.jobmicroservice.dto;

import com.zaina.jobmicroservice.domain.enums.EmploymentType;
import com.zaina.jobmicroservice.domain.enums.JobStatus;
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
    private String title;
    private String description;
    private String location;
    private String workArrangement;
    private String domain;
    private Integer minSalary;
    private Integer maxSalary;
    private Integer openings;
    private Integer hiredCount;
    private EmploymentType employmentType;
    private JobStatus jobStatus;
    private Double skillsWeight;
    private Double semanticWeight;
    private Double experienceWeight;
    private Double seniorityWeight;
    private List<JobRequirementDto> requirements;
    private Instant createdAt;
}