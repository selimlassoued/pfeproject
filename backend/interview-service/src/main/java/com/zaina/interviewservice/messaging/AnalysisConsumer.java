package com.zaina.interviewservice.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaina.interviewservice.config.RabbitMQConfig;
import com.zaina.interviewservice.entities.InterviewResult;
import com.zaina.interviewservice.entities.ProcessingStatus;
import com.zaina.interviewservice.repos.InterviewResultRepo;
import com.zaina.interviewservice.services.AnalysisClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeoutException;

@Component
@RequiredArgsConstructor
@Slf4j
public class AnalysisConsumer {

    private final InterviewResultRepo interviewResultRepo;
    private final AnalysisClient      analysisClient;
    private final ObjectMapper        objectMapper = new ObjectMapper();

    @RabbitListener(queues = RabbitMQConfig.ANALYSIS_QUEUE)
    public void onAnalysisRequest(AnalysisRequestMessage msg) {
        log.info("Analysis job received for interview {} (preFit={} preRec={})",
                msg.getInterviewId(), msg.getJobFitScore(),
                msg.getPreInterviewRecommendation());

        InterviewResult result = interviewResultRepo
                .findByInterviewId(msg.getInterviewId())
                .orElse(null);

        if (result == null) {
            log.error("No InterviewResult row for interview {}", msg.getInterviewId());
            return;
        }

        result.setProcessingStatus(ProcessingStatus.TRANSCRIBING);
        interviewResultRepo.save(result);

        try {
            AnalysisClient.AnalysisResponse resp = analysisClient.analyse(msg);

            result.setTranscript(resp.transcript());
            result.setSummary(resp.summary());
            result.setCandidateScore(resp.candidateScore());
            result.setCandidateStrengths(resp.candidateStrengths());
            result.setCandidateWeaknesses(resp.candidateWeaknesses());
            result.setSuggestedQuestions(resp.suggestedQuestions());
            result.setHiringRecommendation(resp.hiringRecommendation());
            // ── Unified scoring fields ────────────────────────────────────
            result.setPreInterviewScore(resp.preInterviewScore());
            result.setInterviewDelta(resp.interviewDelta());
            result.setFinalScore(resp.finalScore());
            result.setFinalGrade(resp.finalGrade());
            result.setInterviewVerdict(resp.interviewVerdict());
            if (resp.dimensionalScores() != null) {
                try {
                    result.setDimensionalScoresJson(
                            objectMapper.writeValueAsString(resp.dimensionalScores()));
                } catch (JsonProcessingException jpe) {
                    log.warn("Could not serialize dimensional scores for {}: {}",
                            msg.getInterviewId(), jpe.getMessage());
                }
            }
            result.setProcessingStatus(ProcessingStatus.COMPLETED);
            result.setProcessedAt(LocalDateTime.now());
            interviewResultRepo.save(result);

            log.info("Analysis DONE for interview {} — pre={} delta={} final={} grade={} rec={}",
                    msg.getInterviewId(),
                    resp.preInterviewScore(), resp.interviewDelta(),
                    resp.finalScore(), resp.finalGrade(),
                    resp.hiringRecommendation());

        } catch (Exception e) {
            // Log the full stack server-side so debugging is still possible,
            // but persist only a clean user-facing message — the UI surfaces
            // processingError verbatim and a raw reactor / framework string
            // ("Did not observe any item or terminal signal within ... in
            // 'flatMap'...") is gibberish to a recruiter.
            log.error("Analysis FAILED for interview {}: {}", msg.getInterviewId(), e.getMessage(), e);
            result.setProcessingStatus(ProcessingStatus.FAILED);
            result.setProcessingError(toUserMessage(e));
            result.setProcessedAt(LocalDateTime.now());
            interviewResultRepo.save(result);
        }
    }

    private static String toUserMessage(Throwable t) {
        // Walk the cause chain — a reactor TimeoutException usually wraps
        // (or is wrapped by) the operator detail; we want the innermost
        // recognizable cause.
        Throwable cur = t;
        while (cur != null) {
            String name = cur.getClass().getSimpleName();
            if (cur instanceof TimeoutException || name.contains("Timeout")) {
                return "Analysis took longer than the allowed window and was aborted. "
                        + "This is usually a slow Ollama cold start or an overloaded host. "
                        + "Retry from the interview detail page.";
            }
            if (name.contains("Connect") || name.contains("UnknownHost")) {
                return "Could not reach the analysis service. Please retry in a moment.";
            }
            cur = cur.getCause();
        }
        return "Analysis failed. Please retry from the interview detail page.";
    }

}
