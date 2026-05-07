package com.zaina.interviewservice.services;
import com.zaina.interviewservice.entities.Interview;
import com.zaina.interviewservice.entities.InterviewStatus;
import com.zaina.interviewservice.repos.InterviewRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class InterviewStatusScheduler {

    private final InterviewRepo interviewRepo;

    // How long (minutes) an interview is assumed to last before auto-completing
    private static final long INTERVIEW_DURATION_MINUTES = 90;

    @Scheduled(fixedDelay = 60_000) // runs every 60 seconds
    @Transactional
    public void transitionStatuses() {
        try {
            LocalDateTime now = LocalDateTime.now();
            activateScheduled(now);
            completeInProgress(now);
        } catch (Exception e) {
            log.error("InterviewStatusScheduler failed: {}", e.getMessage(), e);
        }
    }

    private void activateScheduled(LocalDateTime now) {
        List<Interview> candidates = interviewRepo.findByStatus(InterviewStatus.SCHEDULED);
        log.info("Scheduler check — SCHEDULED interviews found: {}", candidates.size());

        List<Interview> toActivate = candidates.stream()
                .filter(i -> !now.isBefore(i.getScheduledAt()))
                .toList();

        if (!toActivate.isEmpty()) {
            toActivate.forEach(i -> {
                i.setStatus(InterviewStatus.IN_PROGRESS);
                log.info("Interview {} auto-started (scheduled for {})", i.getId(), i.getScheduledAt());
            });
            interviewRepo.saveAll(toActivate);
        }
    }

    private void completeInProgress(LocalDateTime now) {
        List<Interview> candidates = interviewRepo.findByStatus(InterviewStatus.IN_PROGRESS);
        log.info("Scheduler check — IN_PROGRESS interviews found: {}", candidates.size());

        List<Interview> toComplete = candidates.stream()
                .filter(i -> now.isAfter(i.getScheduledAt().plusMinutes(INTERVIEW_DURATION_MINUTES)))
                .toList();

        if (!toComplete.isEmpty()) {
            toComplete.forEach(i -> {
                i.setStatus(InterviewStatus.COMPLETED);
                i.setCompletedAt(now);
                log.info("Interview {} auto-completed", i.getId());
            });
            interviewRepo.saveAll(toComplete);
        }
    }
}