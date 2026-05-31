package com.zaina.interviewservice.repos;

import com.zaina.interviewservice.entities.DelegationStatus;
import com.zaina.interviewservice.entities.InterviewDelegationRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InterviewDelegationRepo extends JpaRepository<InterviewDelegationRequest, UUID> {
    List<InterviewDelegationRequest> findByInterviewId(UUID interviewId);
    List<InterviewDelegationRequest> findByToRecruiterIdAndStatus(UUID toRecruiterId, DelegationStatus status);
    List<InterviewDelegationRequest> findByFromRecruiterIdAndStatus(UUID fromRecruiterId, DelegationStatus status);
    List<InterviewDelegationRequest> findByStatus(DelegationStatus status);
}
