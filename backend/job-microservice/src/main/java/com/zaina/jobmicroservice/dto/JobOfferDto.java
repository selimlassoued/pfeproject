package com.zaina.jobmicroservice.dto;
import com.zaina.jobmicroservice.domain.enums.EmploymentType;
import com.zaina.jobmicroservice.domain.enums.JobStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobOfferDto {
    private UUID id;
    private String title;
    private String description;
    private String location;
    private Integer minSalary;
    private Integer maxSalary;
    private EmploymentType employmentType;
    private JobStatus jobStatus;
    private List<JobRequirementDto> requirements;
}
