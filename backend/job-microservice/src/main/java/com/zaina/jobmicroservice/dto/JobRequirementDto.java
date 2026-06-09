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

    /** SKILL: BASIC / INTERMEDIATE / ADVANCED / EXPERT, or null = "Any level". */
    private String skillLevel;
    /** SKILL: "HARD" or "SOFT". Null on legacy rows = treat as HARD. */
    private String skillType;
    /** EDUCATION: comma-joined enum tokens (BAC, LICENCE_BACHELOR, MASTER, ...). */
    private String degreeLevel;
    /** EDUCATION: STUDENT / GRADUATE / BOTH, or null = "Either". */
    private String enrollmentType;
    /** EDUCATION: name of the school/university, optional. */
    private String institute;
    /** LANGUAGE: CEFR scale A1 .. C2, or null = "Any level". */
    private String languageLevel;
    /** CERTIFICATION: vendor / organization that issues the cert. */
    private String issuingOrg;
    /** CERTIFICATION: free-text org name when issuingOrg = "OTHER". */
    private String customIssuingOrg;
    /** CERTIFICATION: true = expired certs no longer count as matched. */
    private Boolean requireCurrent;
    /** CERTIFICATION: years of validity after issue date (default per cert). */
    private Integer validityYears;
    /** Hard knockout flag - true = candidate must satisfy this requirement
     *  or be auto-flagged regardless of overall score. */
    private Boolean mustHave;
}
