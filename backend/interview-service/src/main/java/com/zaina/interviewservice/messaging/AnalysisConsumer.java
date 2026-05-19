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
            log.error("Analysis FAILED for interview {}: {}", msg.getInterviewId(), e.getMessage(), e);
            result.setProcessingStatus(ProcessingStatus.FAILED);
            result.setProcessingError(e.getMessage());
            result.setProcessedAt(LocalDateTime.now());
            interviewResultRepo.save(result);
        }
    }

}
