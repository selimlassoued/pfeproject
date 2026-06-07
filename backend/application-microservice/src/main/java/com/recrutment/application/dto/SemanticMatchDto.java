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
    // Non-scoring advisory signals (distance_far, name_mismatch, …) the
    // recruiter should see as banners on the application detail page.
    private List<WarningDto> warnings;
    // Must-have tracking: true when the candidate failed at least one
    // requirement flagged mustHave=true on the job. failedMustHaves lists
    // the descriptions so the UI can name them explicitly.
    private Boolean mustHaveFailed;
    private List<String> failedMustHaves;
    // Job-domain fit (0-100) — how well the candidate's prior work
    // experience matches the job's industry/sector. Null when the job has
    // no domain set or the matcher couldn't compute. evidence carries the
    // matched keywords so the UI can render them as small chips.
    private Integer domainFitScore;
    private List<String> domainMatchEvidence;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WarningDto {
        private String kind;       // distance_far | name_mismatch | …
        private String severity;   // warning | info
        private String message;    // human-readable banner text
        private java.util.Map<String, Object> details;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillScoreDto {
        private String skill;
        private Integer score;             // effective score (post-qualifier-curve)
        private String status;             // "matched" | "partial" | "missing"
        private String evidence;
        private String reason;
        // Qualifier-aware fields surfaced by the Python matcher so the UI
        // can show "clears BASIC bar" / "below ADVANCED bar by 18 points" etc.
        private Integer rawScore;          // pre-curve raw score (0-100)
        private String  qualifier;         // basic / intermediate / advanced / expert / any
        private Integer qualifierBar;      // 45 / 65 / 80 / 90 (0 for 'any')
        private Boolean meetsQualifier;    // raw >= bar
        private Integer gapFromQualifier;  // max(0, bar - raw)
        private String  signal;            // strength / meets / gap / critical_gap / ""
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RequirementScoreDto {
        private String  category;
        private String  description;
        private Integer score;
        private Float   weight;
        private String  evidence;
        // Set on SKILL requirements when the recruiter picked an explicit level:
        private String  skillLevel;        // BASIC / INTERMEDIATE / ADVANCED / EXPERT
        private Boolean criticalGap;       // true when any skill in this req hit critical_gap
        // Must-have tracking per-requirement so the UI can mark the row
        // (orange "Must-have" pill + red "failed" indicator).
        private Boolean mustHave;
        private Boolean mustHaveFailed;
    }
}
