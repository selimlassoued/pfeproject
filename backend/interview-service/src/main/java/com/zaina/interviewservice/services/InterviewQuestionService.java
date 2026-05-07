package com.zaina.interviewservice.services;

import com.zaina.interviewservice.entities.*;
import com.zaina.interviewservice.repos.InterviewQuestionRepo;
import com.zaina.interviewservice.repos.InterviewRepo;
import com.zaina.interviewservice.repos.InterviewResultRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewQuestionService {

    private final InterviewRepo         interviewRepo;
    private final InterviewResultRepo   interviewResultRepo;
    private final InterviewQuestionRepo questionRepo;

    @Value("${analysis.sidecar.url:http://analysis-service:8000}")
    private String sidecarUrl;

    // ── DTOs for sidecar communication ───────────────────────────────────────

    record QuestionGenRequest(
            String job_title,
            String job_description,
            String candidate_name,
            List<String> candidate_skills,
            String candidate_summary,
            String github_score,
            List<String> github_frameworks,
            List<String> cv_weaknesses
    ) {}

    record QuestionGenResponse(
            List<String> technical,
            List<String> behavioral,
            List<String> cv_specific
    ) {}

    // ── Public API ────────────────────────────────────────────────────────────

    @Transactional
    public void generateQuestions(UUID interviewId) {
        Interview interview = interviewRepo.findById(interviewId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Interview not found: " + interviewId));

        // Delete any previously generated questions so regenerating is safe
        questionRepo.deleteByInterviewId(interviewId);

        // Build request — pull whatever context we already have from InterviewResult
        QuestionGenRequest req = buildRequest(interview);

        log.info("Calling sidecar /generate-questions for interview {}", interviewId);

        try {
            QuestionGenResponse resp = WebClient.builder()
                    .baseUrl(sidecarUrl)
                    .build()
                    .post()
                    .uri("/generate-questions")
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(QuestionGenResponse.class)
                    .timeout(java.time.Duration.ofMinutes(5))
                    .block();

            if (resp == null) {
                log.warn("Sidecar returned null for /generate-questions — interview {}", interviewId);
                return;
            }

            List<InterviewQuestion> questions = new ArrayList<>();
            questions.addAll(toEntities(interview, resp.technical(),   "technical"));
            questions.addAll(toEntities(interview, resp.behavioral(),  "behavioral"));
            questions.addAll(toEntities(interview, resp.cv_specific(), "cv_specific"));

            questionRepo.saveAll(questions);
            log.info("Saved {} questions for interview {}", questions.size(), interviewId);

        } catch (Exception e) {
            // Non-fatal — recruiter can still run the interview without suggestions
            log.error("Question generation failed for interview {}: {}", interviewId, e.getMessage());
        }
    }

    public List<InterviewQuestionDto> getQuestions(UUID interviewId) {
        return questionRepo.findByInterviewId(interviewId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public void markQuestion(UUID questionId, String status) {
        InterviewQuestion q = questionRepo.findById(questionId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Question not found: " + questionId));
        try {
            q.setStatus(QuestionStatus.valueOf(status));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid status: " + status + ". Must be ASKED or SKIPPED");
        }
        questionRepo.save(q);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private QuestionGenRequest buildRequest(Interview interview) {
        return new QuestionGenRequest(
                interview.getJobTitle(),
                null,
                interview.getCandidateName(),
                interview.getCandidateSkills(),
                interview.getCandidateSummary(),
                interview.getGithubScore(),
                interview.getGithubFrameworks(),
                interview.getCvWeaknesses()
        );
    }

    private List<InterviewQuestion> toEntities(Interview interview,
                                               List<String> texts,
                                               String category) {
        if (texts == null) return List.of();
        return texts.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(text -> InterviewQuestion.builder()
                        .interview(interview)
                        .text(text)
                        .category(category)
                        .status(QuestionStatus.PENDING)
                        .build())
                .toList();
    }

    private InterviewQuestionDto toDto(InterviewQuestion q) {
        return new InterviewQuestionDto(
                q.getId(),
                q.getText(),
                q.getCategory(),
                q.getStatus().name()
        );
    }

    // ── Response DTO sent to Angular ──────────────────────────────────────────

    public record InterviewQuestionDto(
            UUID   id,
            String text,
            String category,
            String status
    ) {}
}