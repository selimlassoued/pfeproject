package com.recrutment.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SemanticMatchDto {
    private Integer jobFitScore;
    private List<String> requiredSkillsMatched;
    private List<String> requiredSkillsMissing;
    private List<SkillScoreDto> skillScores;
    private Float experienceGap;
    private Boolean seniorityMatch;
    private Integer embeddingScore;
    private List<RequirementScoreDto> requirementScores;
    private List<String> strengths;
    private List<String> weaknesses;
    private String recommendation;
    private List<String> interviewQuestions;
    private String scoreExplanation;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillScoreDto {
        private String skill;
        private Integer score;
        private String status; // "matched" | "partial" | "missing"
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RequirementScoreDto {
        private String category;
        private String description;
        private Integer score;
        private Float weight;
        private String evidence;
    }
}
