package com.recrutment.application.events;

import com.recrutment.application.clients.JobClient;
import com.recrutment.application.clients.JobClient.JobDto;
import com.recrutment.application.messaging.AppEventMessage;
import com.recrutment.application.messaging.AppEventPublisher;
import com.recrutment.application.services.ApplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class HireQuotaListener {

    private final JobClient          jobClient;
    private final ApplicationService applicationService;
    private final AppEventPublisher  eventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional
    public void onHireCompleted(HireCompletedEvent event) {
        try {
            jobClient.incrementHired(event.jobId());
        } catch (Exception e) {
            log.warn("[ATS] incrementHired failed for job {}: {}", event.jobId(), e.getMessage());
        }

        try {
            JobDto updatedJob = jobClient.getJob(event.jobId());
            if (updatedJob == null || updatedJob.getOpenings() == null) return;

            int openings = updatedJob.getOpenings();
            int hired    = updatedJob.getHiredCount() != null ? updatedJob.getHiredCount() : 0;

            if (hired < openings) return;

            // Quota reached — reject remaining pipeline and close job
            int rejected = applicationService.rejectNonHiredForJob(event.jobId());
            log.info("[ATS] Quota reached for job {}. Auto-rejected {} application(s).", event.jobId(), rejected);

            jobClient.closeJob(event.jobId());
            log.info("[ATS] Job {} closed automatically after quota reached.", event.jobId());

            // Notify recruiter
            AppEventMessage quotaEvt = new AppEventMessage();
            quotaEvt.setEventType("JOB_QUOTA_REACHED");
            quotaEvt.setProducer("application-microservice");
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("jobId",           event.jobId().toString());
            payload.put("jobTitle",        event.jobTitle() != null ? event.jobTitle() : "the position");
            payload.put("openings",        openings);
            payload.put("hired",           hired);
            payload.put("rejected",        rejected);
            payload.put("recruiterUserId", event.actorId());
            quotaEvt.setPayload(payload);
            eventPublisher.publish("notify.application", quotaEvt);
            log.info("[ATS] Quota notification sent for job {}.", event.jobId());

        } catch (Exception e) {
            log.error("[ATS] Post-hire quota logic failed for job {}: {}", event.jobId(), e.getMessage());
        }
    }
}
