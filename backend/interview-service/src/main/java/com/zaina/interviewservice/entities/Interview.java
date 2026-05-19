package com.zaina.interviewservice.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "interviews")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Interview {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID applicationId;

    @Column(nullable = false)
    private UUID jobId;

    @Column(nullable = false)
    private UUID recruiterId;

    @Column(nullable = false)
    private UUID candidateId;

    private String candidateEmail;
    private String recruiterEmail;
    private String jobTitle;

    @Column(nullable = false)
    private LocalDateTime scheduledAt;

    @Column(length = 500)
    private String roomUrl;

    private String roomName;

    /** Id of the event mirrored into the recruiter's Google Calendar, if connected. */
    private String googleEventId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean recordingConsent = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private InterviewStatus status = InterviewStatus.SCHEDULED;

    private String recordingId;
    private String recordingUrl;

    @CreationTimestamp
    private LocalDateTime createdAt;

    private LocalDateTime completedAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean recruiterLeft = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean candidateLeft = false;

    /** Recruiters the organizer invited to also join this interview. */
    @ElementCollection
    @CollectionTable(name = "interview_invited_recruiters",
            joinColumns = @JoinColumn(name = "interview_id"))
    @Column(name = "recruiter_id")
    private List<UUID> invitedRecruiterIds;

    // ── CV context — populated at scheduling time from application service ──
    private String candidateName;

    @ElementCollection
    @CollectionTable(name = "interview_candidate_skills",
            joinColumns = @JoinColumn(name = "interview_id"))
    @Column(name = "skill")
    private List<String> candidateSkills;

    @Column(columnDefinition = "TEXT")
    private String candidateSummary;

    private String githubScore;

    @ElementCollection
    @CollectionTable(name = "interview_github_frameworks",
            joinColumns = @JoinColumn(name = "interview_id"))
    @Column(name = "framework")
    private List<String> githubFrameworks;

    @ElementCollection
    @CollectionTable(name = "interview_cv_weaknesses",
            joinColumns = @JoinColumn(name = "interview_id"))
    @Column(name = "weakness")
    private List<String> cvWeaknesses;

    @Column(columnDefinition = "TEXT")
    private String jobDescription;

    @ElementCollection
    @CollectionTable(name = "interview_job_requirements",
            joinColumns = @JoinColumn(name = "interview_id"))
    @Column(name = "requirement", columnDefinition = "TEXT")
    private List<String> jobRequirements;
}