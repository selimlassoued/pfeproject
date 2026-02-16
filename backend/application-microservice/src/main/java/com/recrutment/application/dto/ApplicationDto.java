package com.recrutment.application.dto;

import com.recrutment.application.enums.ApplicationStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApplicationDto {
    private UUID applicationId;
    private UUID jobId;
    private String candidateUserId;
    private String githubUrl;
    private ApplicationStatus status;
    private Instant appliedAt;

    private String cvFileName;
    private String cvContentType;

    private String jobTitle;
    private String candidateName;
}
