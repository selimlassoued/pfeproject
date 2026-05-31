package com.zaina.interviewservice.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Request from the current organizer recruiter to a specific colleague asking
 * them to take over an interview (work duty, sick day, etc.). On accept, the
 * Interview's {@code recruiterId} is reassigned and the original recruiter is
 * demoted to an observer in {@code invitedRecruiterIds}.
 */
@Entity
@Table(name = "interview_delegations")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InterviewDelegationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID interviewId;

    @Column(nullable = false)
    private UUID fromRecruiterId;

    @Column(nullable = false)
    private UUID toRecruiterId;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private DelegationStatus status = DelegationStatus.PENDING;

    /** Auto-expire deadline — typically 1h before interview, capped at +24h. */
    @Column(nullable = false)
    private LocalDateTime deadline;

    @CreationTimestamp
    private LocalDateTime createdAt;

    private LocalDateTime respondedAt;
}
