package com.zaina.interviewservice.dto;

import com.zaina.interviewservice.entities.InterviewProposalStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProposalResponse {
    private UUID id;
    private UUID applicationId;
    private UUID jobId;
    private UUID recruiterId;
    private UUID candidateId;
    private String candidateEmail;
    private String recruiterEmail;
    private String candidateName;
    private String recruiterName;
    private String jobTitle;
    private List<LocalDateTime> proposedSlots;
    private LocalDateTime deadline;
    private InterviewProposalStatus status;
    private LocalDateTime confirmedSlot;
    private UUID interviewId;
    private String message;
    private String declineReason;
    private LocalDateTime createdAt;
    private LocalDateTime respondedAt;
}
