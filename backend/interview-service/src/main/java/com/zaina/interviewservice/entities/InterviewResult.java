package com.zaina.interviewservice.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "interview_results")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InterviewResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne
    @JoinColumn(name = "interview_id", nullable = false)
    private Interview interview;

    // ── Existing fields (keep as-is) ──────────────────────────────────────────
    @Column(columnDefinition = "TEXT")
    private String transcript;

    @Column(columnDefinition = "TEXT")
    private String questions;           // JSON array — kept for legacy

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String softSkillSignals;    // JSON object

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ProcessingStatus processingStatus = ProcessingStatus.PENDING;

    private String processingError;

    @CreationTimestamp
    private LocalDateTime createdAt;

    private LocalDateTime processedAt;

    // ── New fields added by analysis pipeline ─────────────────────────────────
    private Integer candidateScore;     // 1-10

    @ElementCollection
    @CollectionTable(name = "interview_result_strengths",
            joinColumns = @JoinColumn(name = "result_id"))
    @Column(name = "strength", columnDefinition = "TEXT")
    private List<String> candidateStrengths;

    @ElementCollection
    @CollectionTable(name = "interview_result_weaknesses",
            joinColumns = @JoinColumn(name = "result_id"))
    @Column(name = "weakness", columnDefinition = "TEXT")
    private List<String> candidateWeaknesses;

    @ElementCollection
    @CollectionTable(name = "interview_result_suggested_questions",
            joinColumns = @JoinColumn(name = "result_id"))
    @Column(name = "question", columnDefinition = "TEXT")
    private List<String> suggestedQuestions;

    /** STRONG_YES / YES / MAYBE / NO */
    @Column(length = 20)
    private String hiringRecommendation;

    // ── Unified scoring pipeline (Phase 1) ────────────────────────────────
    // These let the UI render the journey: CV+GitHub → Semantic → Interview.
    // Snapshotted at analysis time so the result row stays self-contained
    // even if the underlying CV/match changes later.
    private Integer preInterviewScore;   // 0-100 — semantic match job_fit_score at the time
    private Integer interviewDelta;      // -20..+20 — what the interview added or subtracted
    private Integer finalScore;          // 0-100 — clip(pre + delta)
    /** A+ / A / B / C / D — derived from finalScore. */
    @Column(length = 4)
    private String  finalGrade;
    /** CONFIRMED / RAISED / LOWERED / NEW — interview's effect on the verdict. */
    @Column(length = 20)
    private String  interviewVerdict;
    /** JSON map of {dimension: {score, evidence}}. Kept as TEXT — small enough. */
    @Column(columnDefinition = "TEXT")
    private String  dimensionalScoresJson;
}