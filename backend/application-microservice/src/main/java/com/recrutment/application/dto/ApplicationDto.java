package com.recrutment.application.dto;

import com.recrutment.application.enums.ApplicationStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApplicationDto {
    private UUID applicationId;
    private UUID jobId;
    private String candidateUserId;
    private String githubUrl;
    private ApplicationStatus status;
    private Instant appliedAt;

    private String cvFileName;
    private String cvContentType;

    /** Set when an application is auto-withdrawn (e.g. candidate hired elsewhere). */
    private String withdrawalReason;

    private String jobTitle;
    private String candidateName;
    private String candidateEmail;
    private Integer jobFitScore;
    private java.util.List<String> requiredSkillsMatched;
    private java.util.List<String> requiredSkillsMissing;
    private Float experienceGap;
    private Boolean seniorityMatch;
    private Integer embeddingScore;
    private java.util.List<SemanticMatchDto.SkillScoreDto> skillScores;
    private java.util.List<SemanticMatchDto.RequirementScoreDto> requirementScores;
    private java.util.List<String> strengths;
    private java.util.List<String> weaknesses;
    private String recommendation;
    private java.util.List<String> interviewQuestions;
    private String scoreExplanation;
    private java.util.List<SemanticMatchDto.WarningDto> warnings;
}
