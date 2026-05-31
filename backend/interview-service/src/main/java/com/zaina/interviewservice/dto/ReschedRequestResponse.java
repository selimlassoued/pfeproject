package com.zaina.interviewservice.dto;

import com.zaina.interviewservice.entities.InterviewReschedRequest;
import com.zaina.interviewservice.entities.ReschedRequestStatus;
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
public class ReschedRequestResponse {
    private UUID id;
    private UUID interviewId;
    private InterviewReschedRequest.ProposedBy proposedBy;
    private UUID requesterId;
    private List<LocalDateTime> proposedSlots;
    private LocalDateTime deadline;
    private ReschedRequestStatus status;
    private LocalDateTime confirmedSlot;
    private String message;
    private LocalDateTime createdAt;
    private LocalDateTime respondedAt;
}
