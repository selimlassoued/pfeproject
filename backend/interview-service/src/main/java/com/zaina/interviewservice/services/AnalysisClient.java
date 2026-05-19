package com.zaina.interviewservice.services;

import com.zaina.interviewservice.messaging.AnalysisRequestMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class AnalysisClient {

    private final WebClient webClient;

    public AnalysisClient(
            @Value("${analysis.sidecar.url:http://analysis-service:8000}") String baseUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .build();
        log.info("AnalysisClient targeting {}", baseUrl);
    }

    /**
     * Submit the full pre-interview context plus the interview's RabbitMQ
     * payload to the Python analysis sidecar. The sidecar treats the CV +
     * GitHub + semantic-match signals as the baseline verdict and emits a
     * delta-calibrated final score plus dimensional breakdowns.
     */
    public AnalysisResponse analyse(AnalysisRequestMessage msg) {
        Map<String, Object> body = new HashMap<>();
        body.put("interview_id", msg.getInterviewId().toString());
        body.put("job_title", msg.getJobTitle());
        putIfNotNull(body, "job_description", msg.getJobDescription());
        putIfNotNull(body, "job_requirements", msg.getJobRequirements());
        putIfNotNull(body, "candidate_name", msg.getCandidateName());
        putIfNotNull(body, "recruiter_name", msg.getRecruiterName());
        putIfNotNull(body, "candidate_skills", msg.getCandidateSkills());
        putIfNotNull(body, "candidate_summary", msg.getCandidateSummary());
        putIfNotNull(body, "github_score", msg.getGithubScore());
        putIfNotNull(body, "github_frameworks", msg.getGithubFrameworks());
        putIfNotNull(body, "cv_weaknesses", msg.getCvWeaknesses());
        putIfNotNull(body, "recruiter_joined_at", msg.getRecruiterJoinedAt());
        putIfNotNull(body, "candidate_joined_at", msg.getCandidateJoinedAt());
        // ── Semantic match handoff ────────────────────────────────────────
        putIfNotNull(body, "job_fit_score", msg.getJobFitScore());
        putIfNotNull(body, "pre_interview_recommendation", msg.getPreInterviewRecommendation());
        putIfNotNull(body, "required_skills_matched", msg.getRequiredSkillsMatched());
        putIfNotNull(body, "required_skills_missing", msg.getRequiredSkillsMissing());
        putIfNotNull(body, "semantic_strengths", msg.getSemanticStrengths());
        putIfNotNull(body, "semantic_weaknesses", msg.getSemanticWeaknesses());

        return webClient.post()
                .uri("/analyse")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(AnalysisResponse.class)
                .timeout(java.time.Duration.ofMinutes(30))
                .block();
    }

    private static void putIfNotNull(Map<String, Object> body, String key, Object value) {
        if (value != null) body.put(key, value);
    }

    // ── Response record matching the Python AnalyseResponse schema ────────────
    public record AnalysisResponse(
            String transcript,
            String summary,
            @com.fasterxml.jackson.annotation.JsonProperty("candidate_score")
            int candidateScore,
            @com.fasterxml.jackson.annotation.JsonProperty("candidate_strengths")
            List<String> candidateStrengths,
            @com.fasterxml.jackson.annotation.JsonProperty("candidate_weaknesses")
            List<String> candidateWeaknesses,
            @com.fasterxml.jackson.annotation.JsonProperty("suggested_questions")
            List<String> suggestedQuestions,
            @com.fasterxml.jackson.annotation.JsonProperty("hiring_recommendation")
            String hiringRecommendation,
            // ── Phase-by-phase scoring (Phase 1 of the unified pipeline) ──
            @com.fasterxml.jackson.annotation.JsonProperty("pre_interview_score")
            Integer preInterviewScore,
            @com.fasterxml.jackson.annotation.JsonProperty("interview_delta")
            Integer interviewDelta,
            @com.fasterxml.jackson.annotation.JsonProperty("final_score")
            Integer finalScore,
            @com.fasterxml.jackson.annotation.JsonProperty("final_grade")
            String finalGrade,
            @com.fasterxml.jackson.annotation.JsonProperty("dimensional_scores")
            Map<String, DimensionalScore> dimensionalScores,
            @com.fasterxml.jackson.annotation.JsonProperty("interview_verdict")
            String interviewVerdict
    ) {}

    public record DimensionalScore(
            @com.fasterxml.jackson.annotation.JsonProperty("score")
            Integer score,
            @com.fasterxml.jackson.annotation.JsonProperty("evidence")
            String evidence
    ) {}
}
