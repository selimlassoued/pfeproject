package com.recrutment.application.services;

import com.recrutment.application.clients.CvParserClient;
import com.recrutment.application.entities.Application;
import com.recrutment.application.entities.CvAnalysis;
import com.recrutment.application.repos.CvAnalysisRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CvAnalysisService {

    private final CvParserClient cvParserClient;
    private final CvAnalysisRepo cvAnalysisRepo;

    @Async
    public void analyzeAsync(Application application) {
        try {
            log.info("Starting CV analysis for application: {}", application.getApplicationId());

            CvAnalysis analysis = cvParserClient.analyze(
                    application.getApplicationId(),
                    application.getCvFile(),
                    application.getCvFileName()
            );

            cvAnalysisRepo.save(analysis);
            log.info("CV analysis saved for application: {}", application.getApplicationId());

        } catch (Exception e) {
            log.error("CV analysis failed for application {}: {}", application.getApplicationId(), e.getMessage());
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
}