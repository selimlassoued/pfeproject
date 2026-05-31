package com.zaina.interviewservice.dto;

import com.zaina.interviewservice.entities.DelegationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DelegationResponse {
    private UUID id;
    private UUID interviewId;
    private UUID fromRecruiterId;
    private UUID toRecruiterId;
    private String message;
    private DelegationStatus status;
    private LocalDateTime deadline;
    private LocalDateTime createdAt;
    private LocalDateTime respondedAt;

    // Display helpers — populated on response only so the frontend doesn't have
    // to make a second hop to the user service.
    private String fromRecruiterName;
    private String toRecruiterName;
    private String jobTitle;
    private LocalDateTime interviewScheduledAt;
}
