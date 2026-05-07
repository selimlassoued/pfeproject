package com.zaina.interviewservice.dto;
import com.zaina.interviewservice.entities.InterviewStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
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
    private LocalDateTime scheduledAt;
    private String roomUrl;
    private Boolean recordingConsent;
    private InterviewStatus status;
    private LocalDateTime createdAt;
}
