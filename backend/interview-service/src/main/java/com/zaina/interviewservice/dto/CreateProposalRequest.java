package com.zaina.interviewservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateProposalRequest {
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
    private String message;
}
