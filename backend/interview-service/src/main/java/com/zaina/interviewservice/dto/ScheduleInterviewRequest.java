package com.zaina.interviewservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ScheduleInterviewRequest {
    private UUID applicationId;
    private UUID jobId;
    private UUID recruiterId;
    private UUID candidateId;
    private String candidateEmail;
    private String recruiterEmail;
    private String jobTitle;
    private LocalDateTime scheduledAt;
    private Boolean recordingConsent;
}