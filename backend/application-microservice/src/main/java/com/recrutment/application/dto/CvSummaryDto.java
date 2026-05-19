package com.recrutment.application.dto;
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

    // ── Semantic match handoff to the interview stage ─────────────────────────
    // These fields let the interview analysis treat its scoring as a
    // continuation of the CV+GitHub+semantic verdict instead of a fresh,
    // standalone evaluation. All are nullable (semantic match may not have
    // run yet, or the candidate may have been imported without a JD match).
    private Integer       jobFitScore;            // 0-100
    private String        preInterviewRecommendation; // STRONG_YES / YES / MAYBE / NO
    private List<String>  requiredSkillsMatched;
    private List<String>  requiredSkillsMissing;
    private List<String>  semanticStrengths;
    private List<String>  semanticWeaknesses;
}
