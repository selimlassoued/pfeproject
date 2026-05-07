package com.recrutment.application.repos;

import com.recrutment.application.entities.CvAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CvAnalysisRepo extends JpaRepository<CvAnalysis, UUID> {
    Optional<CvAnalysis> findByApplicationId(UUID applicationId);
    boolean existsByApplicationId(UUID applicationId);

    @Query("SELECT c FROM CvAnalysis c WHERE c.applicationId IN :ids")
    List<CvAnalysis> findByApplicationIdIn(@Param("ids") List<UUID> ids);

    @Modifying
    @Transactional
    @Query("DELETE FROM CvAnalysis c WHERE c.applicationId IN :ids")
    void deleteByApplicationIdIn(@Param("ids") List<UUID> ids);

    @Modifying
    @Transactional
    @Query("DELETE FROM CvAnalysis c WHERE c.applicationId = :applicationId")
    void deleteByApplicationId(@Param("applicationId") UUID applicationId);
}