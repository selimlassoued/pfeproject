package com.recrutment.application.repos;

import com.recrutment.application.entities.Application;
import com.recrutment.application.enums.ApplicationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApplicationRepo extends JpaRepository<Application, UUID> {
    List<Application> findByJobId(UUID jobId);
    List<Application> findByCandidateUserId(String candidateUserId);
    Optional<Application> findByJobIdAndCandidateUserId(UUID jobId, String candidateUserId);
    List<Application> findByStatus(ApplicationStatus status);

    List<Application> findByJobIdAndStatus(UUID jobId, ApplicationStatus status);
    List<Application> findByApplicationId(UUID applicationId);

}