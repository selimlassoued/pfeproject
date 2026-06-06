package com.recrutment.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Recruiter-side summary of one of a candidate's applications.
 *
 * Powers the "candidate history" view on the application-detail page so a
 * recruiter can see — at a glance — every other role this same candidate has
 * applied to here, with status + match score. Deliberately omits the CV
 * bytes and rejection-reason text to keep the payload small and to avoid
 * surfacing bias-anchoring details by default. Recruiters click into a
 * specific row to see full details.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CandidateApplicationSummaryDto {
    private UUID applicationId;
    private UUID jobId;
    /** Best-effort job title fetched from job-microservice; null on miss. */
    private String jobTitle;
    private Instant appliedAt;
    private String status;
    private String previousStatus;
    /** Set when the candidate withdrew; null otherwise. Lets the UI render
     *  withdrawn-by-candidate differently from rejected-by-recruiter. */
    private String withdrawalReason;
    /** 0-100 AI fit score from cv_analysis.semantic_match.jobFitScore, or null
     *  if the match hasn't completed yet. */
    private Integer fitScore;
}
