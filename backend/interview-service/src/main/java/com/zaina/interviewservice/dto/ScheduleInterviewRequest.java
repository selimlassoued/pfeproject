package com.zaina.interviewservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ScheduleInterviewRequest {

    @NotNull private UUID applicationId;
    @NotNull private UUID jobId;
    @NotNull private UUID recruiterId;
    @NotNull private UUID candidateId;

    @Email @Size(max = 120) private String candidateEmail;
    @Email @Size(max = 120) private String recruiterEmail;

    @Size(max = 200) private String candidateName;
    @Size(max = 200) private String recruiterName;
    @Size(max = 200) private String jobTitle;

    @NotNull private LocalDateTime scheduledAt;

    private Boolean recordingConsent;
}