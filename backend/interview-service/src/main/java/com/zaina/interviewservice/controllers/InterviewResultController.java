package com.zaina.interviewservice.controllers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaina.interviewservice.entities.Interview;
import com.zaina.interviewservice.entities.InterviewResult;
import com.zaina.interviewservice.repos.InterviewResultRepo;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/interviews")
@RequiredArgsConstructor
@Slf4j
public class InterviewResultController {

    private final InterviewResultRepo repo;
    private final ObjectMapper        objectMapper = new ObjectMapper();

    @GetMapping("/{id}/result")
    public InterviewResultResponse getResult(@PathVariable UUID id) {
        InterviewResult r = repo.findByInterviewId(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No result for interview " + id));
        return toDto(r);
    }

    private InterviewResultResponse toDto(InterviewResult r) {
        Interview iv = r.getInterview();
        Map<String, DimensionalScore> dims = null;
        if (r.getDimensionalScoresJson() != null && !r.getDimensionalScoresJson().isBlank()) {
            try {
                dims = objectMapper.readValue(
                        r.getDimensionalScoresJson(),
                        new TypeReference<Map<String, DimensionalScore>>() {});
            } catch (Exception e) {
                log.warn("Could not deserialize dimensional scores for result {}: {}",
                        r.getId(), e.getMessage());
            }
        }
        return InterviewResultResponse.builder()
                .id(r.getId())
                .interviewId(iv.getId())
                .processingStatus(r.getProcessingStatus().name())
                .transcript(r.getTranscript())
                .summary(r.getSummary())
                .questions(r.getQuestions())
                .softSkillSignals(r.getSoftSkillSignals())
                .candidateScore(r.getCandidateScore())
                .candidateStrengths(r.getCandidateStrengths())
                .candidateWeaknesses(r.getCandidateWeaknesses())
                .suggestedQuestions(r.getSuggestedQuestions())
                .hiringRecommendation(r.getHiringRecommendation())
                .errorMessage(r.getProcessingError())
                .createdAt(r.getCreatedAt())
                .processedAt(r.getProcessedAt())
                // ── Unified scoring (Phase 1) ─────────────────────────────
                .preInterviewScore(r.getPreInterviewScore())
                .interviewDelta(r.getInterviewDelta())
                .finalScore(r.getFinalScore())
                .finalGrade(r.getFinalGrade())
                .interviewVerdict(r.getInterviewVerdict())
                .dimensionalScores(dims)
                // ── Pre-interview signals snapshotted on the Interview entity
                //    so the UI can render the full journey from one fetch ────
                .candidateName(iv.getCandidateName())
                .recruiterName(iv.getRecruiterName())
                .jobTitle(iv.getJobTitle())
                .candidateSkills(iv.getCandidateSkills())
                .candidateSummary(iv.getCandidateSummary())
                .githubScore(iv.getGithubScore())
                .githubFrameworks(iv.getGithubFrameworks())
                .cvWeaknesses(iv.getCvWeaknesses())
                .jobFitScore(iv.getJobFitScore())
                .preInterviewRecommendation(iv.getPreInterviewRecommendation())
                .requiredSkillsMatched(iv.getRequiredSkillsMatched())
                .requiredSkillsMissing(iv.getRequiredSkillsMissing())
                .semanticStrengths(iv.getSemanticStrengths())
                .semanticWeaknesses(iv.getSemanticWeaknesses())
                .build();
    }

    // Inner DTO — no need for a separate file since it's only used here
    @Data @Builder
    public static class InterviewResultResponse {
        private UUID   id;
        private UUID   interviewId;
        private String processingStatus;
        private String transcript;
        private String summary;
        private String questions;           // legacy JSON
        private String softSkillSignals;    // legacy JSON
        private Integer      candidateScore;
        private List<String> candidateStrengths;
        private List<String> candidateWeaknesses;
        private List<String> suggestedQuestions;
        private String       hiringRecommendation;
        private String       errorMessage;
        private LocalDateTime createdAt;
        private LocalDateTime processedAt;

        // ── Unified phase-by-phase scoring ────────────────────────────────
        private Integer preInterviewScore;
        private Integer interviewDelta;
        private Integer finalScore;
        private String  finalGrade;
        private String  interviewVerdict;
        private Map<String, DimensionalScore> dimensionalScores;

        // ── Pre-interview profile snapshot (so UI doesn't need a second call)
        private String       candidateName;
        private String       recruiterName;
        private String       jobTitle;
        private List<String> candidateSkills;
        private String       candidateSummary;
        private String       githubScore;
        private List<String> githubFrameworks;
        private List<String> cvWeaknesses;
        private Integer      jobFitScore;
        private String       preInterviewRecommendation;
        private List<String> requiredSkillsMatched;
        private List<String> requiredSkillsMissing;
        private List<String> semanticStrengths;
        private List<String> semanticWeaknesses;
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DimensionalScore {
        private Integer score;
        private String  evidence;
    }
}
