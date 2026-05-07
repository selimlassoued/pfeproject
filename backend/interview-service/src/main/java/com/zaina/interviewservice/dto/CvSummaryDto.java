package com.zaina.interviewservice.dto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CvSummaryDto {
    private String      candidateName;
    private List<String> skills;
    private String summary;
    private String githubScore;
    private List<String> githubFrameworks;
    private List<String> cvSkillsNoEvidence;
}
