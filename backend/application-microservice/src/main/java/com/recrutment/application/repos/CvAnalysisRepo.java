package com.recrutment.application.repos;

import com.recrutment.application.entities.CvAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CvAnalysisRepo extends JpaRepository<CvAnalysis, UUID> {
    Optional<CvAnalysis> findByApplicationId(UUID applicationId);
    boolean existsByApplicationId(UUID applicationId);
}