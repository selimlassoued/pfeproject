package com.zaina.interviewservice.messaging;

import com.zaina.interviewservice.RabbitMQConfig;
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

    @RabbitListener(queues = RabbitMQConfig.ANALYSIS_QUEUE)
    public void onAnalysisRequest(AnalysisRequestMessage msg) {
        log.info("Analysis job received for interview {}", msg.getInterviewId());

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
            AnalysisClient.AnalysisResponse resp =
                    analysisClient.analyse(
                            msg.getInterviewId().toString(),
                            msg.getJobTitle(),
                            msg.getCandidateName(),
                            msg.getCandidateSkills(),
                            msg.getCandidateSummary(),
                            msg.getGithubScore(),
                            msg.getRecruiterJoinedAt(),
                            msg.getCandidateJoinedAt()
                    );

            result.setTranscript(resp.transcript());
            result.setSummary(resp.summary());
            result.setCandidateScore(resp.candidateScore());
            result.setCandidateStrengths(resp.candidateStrengths());
            result.setCandidateWeaknesses(resp.candidateWeaknesses());
            result.setSuggestedQuestions(resp.suggestedQuestions());
            result.setHiringRecommendation(resp.hiringRecommendation());
            result.setProcessingStatus(ProcessingStatus.COMPLETED);
            result.setProcessedAt(LocalDateTime.now());
            interviewResultRepo.save(result);

            log.info("Analysis DONE for interview {} — score={} rec={}",
                    msg.getInterviewId(), resp.candidateScore(), resp.hiringRecommendation());

        } catch (Exception e) {
            log.error("Analysis FAILED for interview {}: {}", msg.getInterviewId(), e.getMessage(), e);
            result.setProcessingStatus(ProcessingStatus.FAILED);
            result.setProcessingError(e.getMessage());
            result.setProcessedAt(LocalDateTime.now());
            interviewResultRepo.save(result);
        }
    }

}