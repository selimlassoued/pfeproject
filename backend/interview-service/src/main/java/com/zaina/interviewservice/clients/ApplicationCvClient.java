package com.zaina.interviewservice.clients;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.UUID;

// Service id matches application-microservice's spring.application.name.
// LoadBalancer resolves the registration; no hardcoded host. contextId
// disambiguates from any other Feign client targeting the same service.
@FeignClient(name = "application-microservice", contextId = "applicationCvClient")
public interface ApplicationCvClient {

    @GetMapping("/api/applications/{applicationId}/cv-summary")
    CvSummary getCvSummary(@PathVariable("applicationId") UUID applicationId);

    record CvSummary(
            String candidateName,
            List<String> skills,
            String summary,
            String githubScore,
            List<String> githubFrameworks,
            List<String> cvSkillsNoEvidence,
            // ── Semantic match handoff (nullable when no match has run) ────────
            Integer jobFitScore,
            String preInterviewRecommendation,
            List<String> requiredSkillsMatched,
            List<String> requiredSkillsMissing,
            List<String> semanticStrengths,
            List<String> semanticWeaknesses
    ) {}
}