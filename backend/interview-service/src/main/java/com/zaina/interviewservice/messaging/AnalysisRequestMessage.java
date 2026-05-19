package com.zaina.interviewservice.messaging;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisRequestMessage implements Serializable {
    private UUID         interviewId;
    private String       jobTitle;
    private String       jobDescription;
    private List<String> jobRequirements;
    private String       candidateName;
    private String recruiterName;
    private List<String> candidateSkills;
    private String       candidateSummary;
    private String       githubScore;
    private List<String> githubFrameworks;
    private List<String> cvWeaknesses;
    private  String recruiterJoinedAt;
    private String       candidateJoinedAt;

    // ── Semantic match handoff (nullable) ─────────────────────────────────
    // Pre-interview verdict that the LLM should treat as its baseline; the
    // interview produces a delta against this rather than a fresh 0-10.
    private Integer       jobFitScore;
    private String        preInterviewRecommendation;
    private List<String>  requiredSkillsMatched;
    private List<String>  requiredSkillsMissing;
    private List<String>  semanticStrengths;
    private List<String>  semanticWeaknesses;
}