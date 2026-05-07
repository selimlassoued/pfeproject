package com.recrutment.application.repos;

import com.recrutment.application.entities.Application;
import com.recrutment.application.enums.ApplicationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApplicationRepo extends JpaRepository<Application, UUID> {

    Optional<Application> findByJobIdAndCandidateUserId(UUID jobId, String candidateUserId);

    // Used in apply() — ignores WITHDRAWN so candidate can re-apply after withdrawing
    Optional<Application> findByJobIdAndCandidateUserIdAndStatusNot(
            UUID jobId, String candidateUserId, ApplicationStatus status);

    List<Application> findByCandidateUserId(String candidateUserId);

    // Used in listMyApplications() — excludes WITHDRAWN from candidate dashboard
    List<Application> findByCandidateUserIdAndStatusNot(
            String candidateUserId, ApplicationStatus status);

    List<Application> findByJobId(UUID jobId);

    Page<Application> findByJobId(UUID jobId, Pageable pageable);

    List<Application> findByStatus(ApplicationStatus status);

    Page<Application> findByStatus(ApplicationStatus status, Pageable pageable);

    List<Application> findByJobIdAndStatus(UUID jobId, ApplicationStatus status);

    Page<Application> findByJobIdAndStatus(UUID jobId, ApplicationStatus status, Pageable pageable);

    // ── Moderation queries ────────────────────────────────────────────────────

    List<Application> findByCandidateUserIdAndStatus(String candidateUserId, ApplicationStatus status);

    @Query("SELECT a FROM Application a WHERE a.candidateUserId = :candidateUserId " +
            "AND a.status IN (com.recrutment.application.enums.ApplicationStatus.FLAGGED, " +
            "com.recrutment.application.enums.ApplicationStatus.BLOCKED)")
    List<Application> findActiveModeratedByCandidateUserId(@Param("candidateUserId") String candidateUserId);
}