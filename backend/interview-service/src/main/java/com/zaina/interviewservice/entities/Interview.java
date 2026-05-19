package com.zaina.interviewservice.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
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

    // ── CV context — populated at scheduling time from application service ──
    private String candidateName;
    private String recruiterName;

    @ElementCollection
    @CollectionTable(name = "interview_candidate_skills",
            joinColumns = @JoinColumn(name = "interview_id"))
    @Column(name = "skill")
    @Builder.Default
    private List<String> candidateSkills = new ArrayList<>();

    @Column(columnDefinition = "TEXT")
    private String candidateSummary;

    private String githubScore;

    @Column(columnDefinition = "TEXT")
    private String jobDescription;

    @ElementCollection
    @CollectionTable(name = "interview_github_frameworks",
            joinColumns = @JoinColumn(name = "interview_id"))
    @Column(name = "framework")
    @Builder.Default
    private List<String> githubFrameworks = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "interview_cv_weaknesses",
            joinColumns = @JoinColumn(name = "interview_id"))
    @Column(name = "weakness")
    @Builder.Default
    private List<String> cvWeaknesses = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "interview_job_requirements",
            joinColumns = @JoinColumn(name = "interview_id"))
    @Column(name = "requirement", columnDefinition = "TEXT")
    @Builder.Default
    private List<String> jobRequirements = new ArrayList<>();

    // ── Semantic match handoff (carried from CV/GitHub stage) ──────────────
    // The interview analysis is framed as a continuation of this verdict —
    // confirm, raise, or lower it based on what the candidate demonstrated.
    private Integer jobFitScore;                 // 0-100 from semantic matcher
    private String  preInterviewRecommendation;  // STRONG_YES / YES / MAYBE / NO

    @ElementCollection
    @CollectionTable(name = "interview_skills_matched",
            joinColumns = @JoinColumn(name = "interview_id"))
    @Column(name = "skill")
    @Builder.Default
    private List<String> requiredSkillsMatched = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "interview_skills_missing",
            joinColumns = @JoinColumn(name = "interview_id"))
    @Column(name = "skill")
    @Builder.Default
    private List<String> requiredSkillsMissing = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "interview_semantic_strengths",
            joinColumns = @JoinColumn(name = "interview_id"))
    @Column(name = "strength", columnDefinition = "TEXT")
    @Builder.Default
    private List<String> semanticStrengths = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "interview_semantic_weaknesses",
            joinColumns = @JoinColumn(name = "interview_id"))
    @Column(name = "weakness", columnDefinition = "TEXT")
    @Builder.Default
    private List<String> semanticWeaknesses = new ArrayList<>();
}