package com.zaina.interviewservice.dto;
import com.zaina.interviewservice.entities.InterviewStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class InterviewResponse {
    private UUID id;
    private UUID applicationId;
    private UUID jobId;
    private String jobTitle;
    private String candidateEmail;
    private String recruiterEmail;
    private UUID recruiterId;
    private String candidateName;
    private String recruiterName;
    private LocalDateTime scheduledAt;
    private String roomUrl;
    private Boolean recordingConsent;
    private InterviewStatus status;
    private LocalDateTime createdAt;
    private List<UUID> invitedRecruiterIds;
}
