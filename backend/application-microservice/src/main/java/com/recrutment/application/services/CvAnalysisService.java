package com.recrutment.application.services;

import com.recrutment.application.clients.CvParserClient;
import com.recrutment.application.clients.JobClient;
import com.recrutment.application.clients.JobClient.JobDto;
import com.recrutment.application.dto.SemanticMatchDto;
import com.recrutment.application.entities.Application;
import com.recrutment.application.entities.CvAnalysis;
import com.recrutment.application.repos.CvAnalysisRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.recrutment.application.repos.ApplicationRepo;
import com.recrutment.application.enums.ApplicationStatus;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CvAnalysisService {

    private final CvParserClient cvParserClient;
    private final CvAnalysisRepo cvAnalysisRepo;
    private final JobClient jobClient;
    private final ApplicationRepo applicationRepo;

    /**
     * Single async job — CV parsing and GitHub enrichment run in parallel
     * inside the cv-parser-service (Python handles the threading).
     * Spring Boot just sends one request and waits for the complete result.
     */
    @Async
    public void analyzeAsync(Application application) {
        try {
            log.info("Starting CV analysis for application: {}", application.getApplicationId());

            // Recompute policy: overwrite any previous analysis/match
            if (cvAnalysisRepo.existsByApplicationId(application.getApplicationId())) {
                cvAnalysisRepo.deleteByApplicationId(application.getApplicationId());
                log.info("Deleted previous CV analysis for application: {}", application.getApplicationId());
            }

            CvAnalysis analysis = cvParserClient.analyze(
                    application.getApplicationId(),
                    application.getCvFile(),
                    application.getCvFileName(),
                    application.getGithubUrl()  // passed to Python for parallel processing
            );

            // Persist parsed CV first
            CvAnalysis saved = cvAnalysisRepo.save(analysis);
            log.info("CV analysis saved for application: {}", application.getApplicationId());

            // Compute semantic matching once and persist in the same row
            try {
                JobDto job = jobClient.getJob(application.getJobId());
                if (job != null) {
                    SemanticMatchDto match = cvParserClient.match(application.getApplicationId(), saved, job);
                    saved.setSemanticMatch(match);
                    cvAnalysisRepo.save(saved);
                    log.info("Semantic match saved for application: {}", application.getApplicationId());
                } else {
                    log.warn("Job service unavailable — semantic match skipped for {}", application.getApplicationId());
                }
            } catch (Exception e) {
                log.error("Semantic match failed for application {}: {}",
                        application.getApplicationId(), e.getMessage());
            }

        } catch (Exception e) {
            log.error("CV analysis failed for application {}: {}",
                    application.getApplicationId(), e.getMessage());
        }
    }

    public CvAnalysis getAnalysis(UUID applicationId) {
        return cvAnalysisRepo.findByApplicationId(applicationId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "CV analysis not found for application: " + applicationId
                ));
    }

    public boolean hasAnalysis(UUID applicationId) {
        return cvAnalysisRepo.existsByApplicationId(applicationId);
    }

    public SemanticMatchDto getSemanticMatch(UUID applicationId, JobDto job) {
        CvAnalysis analysis = getAnalysis(applicationId);
        // Prefer persisted match (computed once on UNDER_REVIEW)
        if (analysis.getSemanticMatch() != null) return analysis.getSemanticMatch();
        // Fallback (should be rare): compute on-demand
        SemanticMatchDto match = cvParserClient.match(applicationId, analysis, job);
        try {
            analysis.setSemanticMatch(match);
            cvAnalysisRepo.save(analysis);
        } catch (Exception ignored) {}
        return match;
    }

    public SemanticMatchDto getStoredSemanticMatch(UUID applicationId) {
        return cvAnalysisRepo.findByApplicationId(applicationId)
                .map(CvAnalysis::getSemanticMatch)
                .orElse(null);
    }

    public void retriggerAnalysis(Application application) {
        analyzeAsync(application);
    }

    /**
     * Re-run semantic matching only (no CV re-parse) for every active application of a job.
     * Called when job requirements or scoring weights are updated by the recruiter.
     * Uses the already-parsed CvAnalysis stored in DB — only the match step is re-executed.
     */
    @Async
    public void rematchAllForJob(UUID jobId) {
        log.info("[Rematch] Starting re-match for all applications of job: {}", jobId);
        JobDto job = jobClient.getJob(jobId);
        if (job == null) {
            log.warn("[Rematch] Job {} not found — aborting rematch", jobId);
            return;
        }
        List<Application> apps = applicationRepo.findByJobId(jobId).stream()
                .filter(a -> a.getStatus() != ApplicationStatus.WITHDRAWN)
                .toList();
        log.info("[Rematch] {} applications to rematch for job {}", apps.size(), jobId);

        for (Application app : apps) {
            try {
                CvAnalysis analysis = cvAnalysisRepo.findByApplicationId(app.getApplicationId())
                        .orElse(null);
                if (analysis == null || !"SUCCESS".equals(analysis.getParsingStatus())) {
                    log.info("[Rematch] Skipping {} — no valid analysis", app.getApplicationId());
                    continue;
                }
                SemanticMatchDto match = cvParserClient.match(app.getApplicationId(), analysis, job);
                analysis.setSemanticMatch(match);
                cvAnalysisRepo.save(analysis);
                log.info("[Rematch] Updated match for application {}", app.getApplicationId());
            } catch (Exception e) {
                log.error("[Rematch] Failed for application {}: {}", app.getApplicationId(), e.getMessage());
            }
        }
        log.info("[Rematch] Done for job {}", jobId);
    }
}