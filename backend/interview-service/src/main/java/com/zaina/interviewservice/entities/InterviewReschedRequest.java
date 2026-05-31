package com.zaina.interviewservice.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A pending request to reschedule an already-SCHEDULED interview. Either side
 * (recruiter or candidate) can issue one. The original interview's time stays
 * valid until the other party picks one of the proposed slots — then the
 * Interview entity is updated in-place (same id, same room, updated
 * scheduledAt) and the Google Calendar event is patched.
 */
@Entity
@Table(name = "interview_resched_requests")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InterviewReschedRequest {

    public enum ProposedBy { RECRUITER, CANDIDATE }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID interviewId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProposedBy proposedBy;

    @Column(nullable = false)
    private UUID requesterId;

    @ElementCollection
    @CollectionTable(name = "interview_resched_slots",
            joinColumns = @JoinColumn(name = "request_id"))
    @Column(name = "slot_at", nullable = false)
    @Builder.Default
    private List<LocalDateTime> proposedSlots = new ArrayList<>();

    @Column(nullable = false)
    private LocalDateTime deadline;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ReschedRequestStatus status = ReschedRequestStatus.PENDING;

    private LocalDateTime confirmedSlot;

    @Column(columnDefinition = "TEXT")
    private String message;

    @CreationTimestamp
    private LocalDateTime createdAt;

    private LocalDateTime respondedAt;
}
