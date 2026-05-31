package com.zaina.interviewservice.repos;

import com.zaina.interviewservice.entities.InterviewProposal;
import com.zaina.interviewservice.entities.InterviewProposalStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InterviewProposalRepo extends JpaRepository<InterviewProposal, UUID> {
    List<InterviewProposal> findByApplicationId(UUID applicationId);
    List<InterviewProposal> findByCandidateId(UUID candidateId);
    List<InterviewProposal> findByRecruiterId(UUID recruiterId);
    List<InterviewProposal> findByStatus(InterviewProposalStatus status);
}
