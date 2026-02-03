package com.zaina.jobmicroservice.dto;
import com.zaina.jobmicroservice.domain.enums.RequirementCategory;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobRequirementDto {
    private UUID id;
    private RequirementCategory category;
    private String description;
    private Double weight;
    private Integer minYears;
    private Integer maxYears;
}
