package com.zaina.interviewservice.controllers;

import com.zaina.interviewservice.entities.InterviewResult;
import com.zaina.interviewservice.repos.InterviewResultRepo;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/interviews")
@RequiredArgsConstructor
public class InterviewResultController {

    private final InterviewResultRepo repo;

    @GetMapping("/{id}/result")
    public InterviewResultResponse getResult(@PathVariable UUID id) {
        InterviewResult r = repo.findByInterviewId(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No result for interview " + id));
        return toDto(r);
    }

    private InterviewResultResponse toDto(InterviewResult r) {
        return InterviewResultResponse.builder()
                .id(r.getId())
                .interviewId(r.getInterview().getId())
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
    }
}