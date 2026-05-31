package com.zaina.interviewservice.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A recruiter-issued offer of multiple candidate-pickable interview slots.
 * Candidate has until {@link #deadline} to pick one; otherwise the proposal
 * auto-expires and the recruiter can re-issue. On pick, a real Interview is
 * created via the normal scheduling pipeline (Google Calendar invite, status
 * transition, CV context, etc.) and the proposal flips to CONFIRMED.
 */
@Entity
@Table(name = "interview_proposals")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InterviewProposal {

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
    private String candidateName;
    private String recruiterName;
    private String jobTitle;

    /** 2-4 candidate-pickable times. Order is the order the recruiter entered them. */
    @ElementCollection
    @CollectionTable(name = "interview_proposal_slots",
            joinColumns = @JoinColumn(name = "proposal_id"))
    @Column(name = "slot_at", nullable = false)
    @Builder.Default
    private List<LocalDateTime> proposedSlots = new ArrayList<>();

    /** When this proposal stops accepting picks. */
    @Column(nullable = false)
    private LocalDateTime deadline;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private InterviewProposalStatus status = InterviewProposalStatus.PENDING;

    /** Set when the candidate picks — null until then. */
    private LocalDateTime confirmedSlot;

    /** Id of the Interview entity created on pick. */
    private UUID interviewId;

    /** Optional recruiter note shown to the candidate on the pick screen. */
    @Column(columnDefinition = "TEXT")
    private String message;

    /** Candidate's reason when they decline (none of the slots work). */
    @Column(columnDefinition = "TEXT")
    private String declineReason;

    @CreationTimestamp
    private LocalDateTime createdAt;

    private LocalDateTime respondedAt;
}
