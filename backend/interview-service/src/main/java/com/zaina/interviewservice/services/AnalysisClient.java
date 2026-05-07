package com.zaina.interviewservice.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

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

    // AnalysisClient.java
    public AnalysisResponse analyse(String interviewId, String jobTitle,
                                    String candidateName, List<String> candidateSkills,
                                    String candidateSummary, String githubScore,
                                    String recruiterJoinedAt, String candidateJoinedAt) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("interview_id", interviewId);
        body.put("job_title", jobTitle);
        if (candidateName   != null) body.put("candidate_name",    candidateName);
        if (candidateSkills != null) body.put("candidate_skills",  candidateSkills);
        if (candidateSummary != null) body.put("candidate_summary", candidateSummary);
        if (githubScore     != null) body.put("github_score",      githubScore);
        if (recruiterJoinedAt != null) body.put("recruiter_joined_at", recruiterJoinedAt);  // ← new
        if (candidateJoinedAt != null) body.put("candidate_joined_at", candidateJoinedAt);

        return webClient.post()
                .uri("/analyse")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(AnalysisResponse.class)
                .timeout(java.time.Duration.ofMinutes(30))
                .block();
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
            String hiringRecommendation
    ) {}
}