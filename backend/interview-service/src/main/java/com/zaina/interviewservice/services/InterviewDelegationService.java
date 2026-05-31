package com.zaina.interviewservice.services;

import com.zaina.interviewservice.clients.UserClient;
import com.zaina.interviewservice.dto.CreateDelegationRequest;
import com.zaina.interviewservice.dto.DelegationResponse;
import com.zaina.interviewservice.entities.DelegationStatus;
import com.zaina.interviewservice.entities.Interview;
import com.zaina.interviewservice.entities.InterviewDelegationRequest;
import com.zaina.interviewservice.entities.InterviewStatus;
import com.zaina.interviewservice.exceptions.ConflictException;
import com.zaina.interviewservice.messaging.AppEventMessage;
import com.zaina.interviewservice.messaging.InterviewEventPublisher;
import com.zaina.interviewservice.repos.InterviewDelegationRepo;
import com.zaina.interviewservice.repos.InterviewRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Hand off an interview to another recruiter when the organizer can't make it.
 * Distinct from the existing "invite recruiter" flow which only adds observers;
 * this transfers organizer-level control (admit / cancel / reschedule).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewDelegationService {

    /** Auto-expire window — capped at 24h from creation OR 1h before interview, whichever is sooner. */
    private static final int MAX_DEADLINE_HOURS = 24;
    private static final int MIN_BUFFER_BEFORE_INTERVIEW_MINUTES = 60;

    private final InterviewDelegationRepo repo;
    private final InterviewRepo interviewRepo;
    private final GoogleCalendarService googleCalendarService;
    private final InterviewEventPublisher eventPublisher;
    private final UserClient userClient;

    // ── Propose ─────────────────────────────────────────────────────────────
    @Transactional
    public DelegationResponse propose(UUID interviewId,
                                       CreateDelegationRequest req,
                                       UUID fromRecruiterId) {
        Interview interview = findInterview(interviewId);

        if (interview.getStatus() != InterviewStatus.SCHEDULED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only SCHEDULED interviews can be delegated (current: "
                            + interview.getStatus() + ").");
        }
        if (!interview.getRecruiterId().equals(fromRecruiterId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the current organizer can delegate this interview.");
        }
        if (req.getToRecruiterId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Target recruiter is required.");
        }
        if (req.getToRecruiterId().equals(fromRecruiterId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot delegate to yourself.");
        }

        // No stacked delegations on the same interview.
        boolean hasPending = repo.findByInterviewId(interviewId).stream()
                .anyMatch(d -> d.getStatus() == DelegationStatus.PENDING);
        if (hasPending) {
            throw new ConflictException(
                    "There's already a pending delegation request for this interview. " +
                            "Cancel it first or wait for a response.");
        }

        // Deadline: min(now + 24h, interview start - 1h). Reject if the
        // interview is already too close.
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime maxByWindow = now.plusHours(MAX_DEADLINE_HOURS);
        LocalDateTime maxBeforeInterview = interview.getScheduledAt()
                .minusMinutes(MIN_BUFFER_BEFORE_INTERVIEW_MINUTES);
        if (maxBeforeInterview.isBefore(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Interview is too close to delegate. Cancel or attend.");
        }
        LocalDateTime deadline = maxByWindow.isBefore(maxBeforeInterview)
                ? maxByWindow : maxBeforeInterview;

        InterviewDelegationRequest entity = InterviewDelegationRequest.builder()
                .interviewId(interviewId)
                .fromRecruiterId(fromRecruiterId)
                .toRecruiterId(req.getToRecruiterId())
                .message(req.getMessage())
                .deadline(deadline)
                .status(DelegationStatus.PENDING)
                .build();
        InterviewDelegationRequest saved = repo.save(entity);
        log.info("Delegation {} created — interview {} from {} to {}",
                saved.getId(), interviewId, fromRecruiterId, req.getToRecruiterId());

        publishEvent("INTERVIEW_DELEGATION_REQUESTED", saved, interview);
        return toResponse(saved, interview);
    }

    // ── Accept (target takes over) ──────────────────────────────────────────
    @Transactional
    public DelegationResponse accept(UUID requestId, UUID acceptorId) {
        InterviewDelegationRequest request = findRequest(requestId);
        if (request.getStatus() != DelegationStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This delegation is " + request.getStatus().name().toLowerCase() + ".");
        }
        if (request.getDeadline().isBefore(LocalDateTime.now())) {
            request.setStatus(DelegationStatus.EXPIRED);
            repo.save(request);
            throw new ResponseStatusException(HttpStatus.GONE,
                    "This delegation request has expired.");
        }
        if (!request.getToRecruiterId().equals(acceptorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the targeted recruiter can accept this delegation.");
        }

        Interview interview = findInterview(request.getInterviewId());
        if (interview.getStatus() != InterviewStatus.SCHEDULED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The interview is no longer SCHEDULED — handoff aborted.");
        }

        UUID originalRecruiter = interview.getRecruiterId();

        // ── Reassign organizer ─────────────────────────────────────────────
        interview.setRecruiterId(acceptorId);

        // Demote the original to an observer so they keep visibility +
        // can still join the room if their schedule frees up.
        List<UUID> invited = interview.getInvitedRecruiterIds();
        if (invited == null) invited = new ArrayList<>();
        if (originalRecruiter != null && !invited.contains(originalRecruiter)) {
            invited.add(originalRecruiter);
        }
        // The new organizer should NOT also be in invitedRecruiterIds.
        invited.remove(acceptorId);
        interview.setInvitedRecruiterIds(invited);

        // Refresh the recruiter display name from the user service so the
        // candidate sees the right name on their dashboard.
        try {
            UserClient.UserProfile newRecruiterProfile = userClient.getUserProfile(acceptorId);
            String newName = newRecruiterProfile.fullName();
            if (newName != null && !newName.isBlank()) {
                interview.setRecruiterName(newName);
            }
            if (newRecruiterProfile.email() != null && !newRecruiterProfile.email().isBlank()) {
                interview.setRecruiterEmail(newRecruiterProfile.email());
            }
        } catch (Exception e) {
            log.warn("Could not refresh recruiter profile {}: {}", acceptorId, e.getMessage());
        }

        interviewRepo.save(interview);

        // ── Mirror onto the new recruiter's Google Calendar (if linked) ───
        // The old event stays on the original recruiter's calendar so they
        // still have a record; they can delete it manually if they wish.
        try {
            String newEventId = googleCalendarService.createInterviewEvent(
                    acceptorId,
                    interview.getJobTitle(),
                    interview.getCandidateEmail(),
                    interview.getScheduledAt(),
                    interview.getRoomUrl());
            if (newEventId != null) {
                interview.setGoogleEventId(newEventId);
                interviewRepo.save(interview);
            }
        } catch (Exception e) {
            log.warn("Could not push handed-off interview {} to Google Calendar: {}",
                    interview.getId(), e.getMessage());
        }

        request.setStatus(DelegationStatus.ACCEPTED);
        request.setRespondedAt(LocalDateTime.now());
        InterviewDelegationRequest saved = repo.save(request);

        log.info("Delegation {} accepted — interview {} now owned by {}",
                requestId, interview.getId(), acceptorId);
        publishEvent("INTERVIEW_DELEGATION_ACCEPTED", saved, interview);
        return toResponse(saved, interview);
    }

    // ── Decline ─────────────────────────────────────────────────────────────
    @Transactional
    public DelegationResponse decline(UUID requestId, UUID requesterId) {
        InterviewDelegationRequest request = findRequest(requestId);
        if (request.getStatus() != DelegationStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Already responded.");
        }
        if (!request.getToRecruiterId().equals(requesterId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the targeted recruiter can decline this delegation.");
        }
        request.setStatus(DelegationStatus.DECLINED);
        request.setRespondedAt(LocalDateTime.now());
        InterviewDelegationRequest saved = repo.save(request);
        Interview interview = findInterview(request.getInterviewId());
        publishEvent("INTERVIEW_DELEGATION_DECLINED", saved, interview);
        return toResponse(saved, interview);
    }

    // ── Cancel (sender withdraws) ───────────────────────────────────────────
    @Transactional
    public DelegationResponse cancel(UUID requestId, UUID requesterId, boolean admin) {
        InterviewDelegationRequest request = findRequest(requestId);
        if (request.getStatus() != DelegationStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Already responded.");
        }
        if (!admin && !request.getFromRecruiterId().equals(requesterId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the requester can cancel a delegation request.");
        }
        request.setStatus(DelegationStatus.CANCELLED);
        request.setRespondedAt(LocalDateTime.now());
        InterviewDelegationRequest saved = repo.save(request);
        Interview interview = findInterview(request.getInterviewId());
        publishEvent("INTERVIEW_DELEGATION_CANCELLED", saved, interview);
        return toResponse(saved, interview);
    }

    // ── Auto-expire ─────────────────────────────────────────────────────────
    @Transactional
    public int expirePending(LocalDateTime now) {
        List<InterviewDelegationRequest> pending = repo.findByStatus(DelegationStatus.PENDING);
        int n = 0;
        for (InterviewDelegationRequest r : pending) {
            if (r.getDeadline() != null && now.isAfter(r.getDeadline())) {
                r.setStatus(DelegationStatus.EXPIRED);
                r.setRespondedAt(now);
                repo.save(r);
                n++;
            }
        }
        return n;
    }

    // ── Reads ───────────────────────────────────────────────────────────────
    public List<DelegationResponse> getIncoming(UUID recruiterId) {
        return repo.findByToRecruiterIdAndStatus(recruiterId, DelegationStatus.PENDING).stream()
                .map(r -> toResponse(r, interviewRepo.findById(r.getInterviewId()).orElse(null)))
                .collect(Collectors.toList());
    }

    public List<DelegationResponse> getOutgoing(UUID recruiterId) {
        return repo.findByFromRecruiterIdAndStatus(recruiterId, DelegationStatus.PENDING).stream()
                .map(r -> toResponse(r, interviewRepo.findById(r.getInterviewId()).orElse(null)))
                .collect(Collectors.toList());
    }

    public List<DelegationResponse> getByInterview(UUID interviewId) {
        return repo.findByInterviewId(interviewId).stream()
                .map(r -> toResponse(r, interviewRepo.findById(interviewId).orElse(null)))
                .collect(Collectors.toList());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────
    private Interview findInterview(UUID id) {
        return interviewRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Interview not found: " + id));
    }

    private InterviewDelegationRequest findRequest(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Delegation request not found: " + id));
    }

    private void publishEvent(String type, InterviewDelegationRequest r, Interview interview) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("delegationId", r.getId().toString());
            payload.put("interviewId", interview.getId().toString());
            payload.put("applicationId", interview.getApplicationId().toString());
            payload.put("fromRecruiterId", r.getFromRecruiterId().toString());
            payload.put("toRecruiterId",   r.getToRecruiterId().toString());
            payload.put("candidateId", interview.getCandidateId().toString());
            payload.put("jobTitle", interview.getJobTitle() != null
                    ? interview.getJobTitle() : "an interview");
            AppEventMessage evt = new AppEventMessage();
            evt.setEventType(type);
            evt.setProducer("interview-service");
            evt.setPayload(payload);
            eventPublisher.publish("notify.interview", evt);
        } catch (Exception e) {
            log.warn("Could not publish {} event: {}", type, e.getMessage());
        }
    }

    private DelegationResponse toResponse(InterviewDelegationRequest r, Interview interview) {
        String fromName = tryFetchName(r.getFromRecruiterId());
        String toName   = tryFetchName(r.getToRecruiterId());
        return DelegationResponse.builder()
                .id(r.getId())
                .interviewId(r.getInterviewId())
                .fromRecruiterId(r.getFromRecruiterId())
                .toRecruiterId(r.getToRecruiterId())
                .message(r.getMessage())
                .status(r.getStatus())
                .deadline(r.getDeadline())
                .createdAt(r.getCreatedAt())
                .respondedAt(r.getRespondedAt())
                .fromRecruiterName(fromName)
                .toRecruiterName(toName)
                .jobTitle(interview != null ? interview.getJobTitle() : null)
                .interviewScheduledAt(interview != null ? interview.getScheduledAt() : null)
                .build();
    }

    private String tryFetchName(UUID userId) {
        try {
            UserClient.UserProfile p = userClient.getUserProfile(userId);
            String n = p.fullName();
            return (n == null || n.isBlank()) ? null : n;
        } catch (Exception e) {
            return null;
        }
    }
}
