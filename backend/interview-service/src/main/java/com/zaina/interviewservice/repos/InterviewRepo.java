package com.zaina.interviewservice.repos;

import com.zaina.interviewservice.entities.Interview;
import com.zaina.interviewservice.entities.InterviewStatus;
import feign.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface InterviewRepo extends JpaRepository<Interview, UUID> {
    List<Interview> findByApplicationId(UUID applicationId);
    List<Interview> findByCandidateId(UUID candidateId);
    List<Interview> findByRecruiterId(UUID recruiterId);
    List<Interview> findByStatus(InterviewStatus status);
    @Modifying
    @Query("""
    UPDATE Interview i SET i.recruiterLeft = true
    WHERE i.id = :id
      AND i.status NOT IN (com.zaina.interviewservice.entities.InterviewStatus.CANCELLED,
                           com.zaina.interviewservice.entities.InterviewStatus.COMPLETED)
""")
    int markRecruiterLeft(@Param("id") UUID id);

    @Modifying
    @Query("""
    UPDATE Interview i SET i.candidateLeft = true
    WHERE i.id = :id
      AND i.status NOT IN (com.zaina.interviewservice.entities.InterviewStatus.CANCELLED,
                           com.zaina.interviewservice.entities.InterviewStatus.COMPLETED)
""")
    int markCandidateLeft(@Param("id") UUID id);
}