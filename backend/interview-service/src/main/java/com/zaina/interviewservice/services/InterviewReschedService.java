package com.zaina.interviewservice.services;

import com.zaina.interviewservice.dto.CreateReschedRequest;
import com.zaina.interviewservice.dto.ReschedRequestResponse;
import com.zaina.interviewservice.entities.Interview;
import com.zaina.interviewservice.entities.InterviewReschedRequest;
import com.zaina.interviewservice.entities.InterviewStatus;
import com.zaina.interviewservice.entities.ReschedRequestStatus;
import com.zaina.interviewservice.exceptions.ConflictException;
import com.zaina.interviewservice.messaging.AppEventMessage;
import com.zaina.interviewservice.messaging.InterviewEventPublisher;
import com.zaina.interviewservice.repos.InterviewReschedRequestRepo;
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
 * Reschedule existing SCHEDULED interviews. Both recruiter and candidate can
 * propose new times; the original time stays valid until the other party
 * picks one of the proposed slots (or the request expires).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewReschedService {

    private static final int MIN_SLOTS = 1;
    private static final int MAX_SLOTS = 4;
    /** Industry-norm: reschedules must give at least this much notice. */
    private static final int MIN_NOTICE_MINUTES = 120;

    private final InterviewReschedRequestRepo requestRepo;
    private final InterviewRepo interviewRepo;
    private final GoogleCalendarService googleCalendarService;
    private final InterviewEventPublisher eventPublisher;

    // ── Propose ─────────────────────────────────────────────────────────────
    @Transactional
    public ReschedRequestResponse propose(UUID interviewId,
                                          CreateReschedRequest req,
                                          UUID requesterId,
                                          InterviewReschedRequest.ProposedBy role) {
        Interview interview = findInterview(interviewId);

        if (interview.getStatus() != InterviewStatus.SCHEDULED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only SCHEDULED interviews can be rescheduled (current: "
                            + interview.getStatus() + ").");
        }

        // Min-notice rule on the ORIGINAL time — don't allow last-minute
        // reschedules right before the interview is about to start.
        long minutesUntil = Duration.between(LocalDateTime.now(),
                interview.getScheduledAt()).toMinutes();
        if (minutesUntil < MIN_NOTICE_MINUTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This interview is too close to start to be rescheduled. " +
                            "Contact the other party directly or cancel.");
        }

        // Permission check — the requester must be a party to the interview.
        if (role == InterviewReschedRequest.ProposedBy.RECRUITER
                && !interview.getRecruiterId().equals(requesterId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the recruiter who scheduled this interview can reschedule it.");
        }
        if (role == InterviewReschedRequest.ProposedBy.CANDIDATE
                && !interview.getCandidateId().equals(requesterId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the candidate on this interview can reschedule it.");
        }

        List<LocalDateTime> slots = req.getProposedSlots();
        if (slots == null || slots.size() < MIN_SLOTS || slots.size() > MAX_SLOTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Propose " + MIN_SLOTS + " to " + MAX_SLOTS + " slots.");
        }
        LocalDateTime now = LocalDateTime.now();
        if (slots.stream().anyMatch(s -> s == null || s.isBefore(now))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "All proposed slots must be in the future.");
        }
        if (slots.stream().distinct().count() != slots.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Proposed slots must be distinct.");
        }
        if (req.getDeadline() == null || req.getDeadline().isBefore(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Deadline must be in the future.");
        }

        // Block stacked requests — only one pending reschedule at a time.
        boolean hasPending = requestRepo.findByInterviewId(interviewId).stream()
                .anyMatch(r -> r.getStatus() == ReschedRequestStatus.PENDING);
        if (hasPending) {
            throw new ConflictException(
                    "There's already a pending reschedule request for this interview. " +
                            "Wait for a response or cancel it first.");
        }

        InterviewReschedRequest entity = InterviewReschedRequest.builder()
                .interviewId(interviewId)
                .proposedBy(role)
                .requesterId(requesterId)
                .proposedSlots(new ArrayList<>(slots))
                .deadline(req.getDeadline())
                .message(req.getMessage())
                .status(ReschedRequestStatus.PENDING)
                .build();
        InterviewReschedRequest saved = requestRepo.save(entity);

        log.info("Reschedule request {} created for interview {} by {} ({})",
                saved.getId(), interviewId, role, requesterId);
        publishEvent("INTERVIEW_RESCHEDULE_PROPOSED", saved, interview);
        return toResponse(saved);
    }

    // ── Accept (pick a new slot) ────────────────────────────────────────────
    @Transactional
    public ReschedRequestResponse accept(UUID requestId, int slotIndex, UUID requesterId) {
        InterviewReschedRequest request = findRequest(requestId);

        if (request.getStatus() != ReschedRequestStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This reschedule request is " + request.getStatus().name().toLowerCase() + ".");
        }
        if (request.getDeadline().isBefore(LocalDateTime.now())) {
            request.setStatus(ReschedRequestStatus.EXPIRED);
            requestRepo.save(request);
            throw new ResponseStatusException(HttpStatus.GONE,
                    "This reschedule request has expired.");
        }
        if (slotIndex < 0 || slotIndex >= request.getProposedSlots().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "slotIndex is out of range.");
        }

        Interview interview = findInterview(request.getInterviewId());

        // Only the OTHER party may accept. Recruiter proposed → candidate accepts; and vice-versa.
        if (request.getProposedBy() == InterviewReschedRequest.ProposedBy.RECRUITER
                && !interview.getCandidateId().equals(requesterId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the candidate can accept this reschedule request.");
        }
        if (request.getProposedBy() == InterviewReschedRequest.ProposedBy.CANDIDATE
                && !interview.getRecruiterId().equals(requesterId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the recruiter can accept this reschedule request.");
        }

        LocalDateTime newTime = request.getProposedSlots().get(slotIndex);
        if (newTime.isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That slot has already passed — please ask for new times.");
        }

        // ── Apply the change in-place ──────────────────────────────────────
        interview.setScheduledAt(newTime);
        // The candidate has to be re-admitted on the rescheduled date — clear
        // the prior flag so the room access flow re-runs.
        interview.setCandidateAdmitted(false);
        interviewRepo.save(interview);

        // Patch Google Calendar event (no-op if recruiter never linked it).
        try {
            googleCalendarService.updateInterviewEvent(
                    interview.getRecruiterId(),
                    interview.getGoogleEventId(),
                    newTime);
        } catch (Exception e) {
            log.warn("Could not update Google Calendar event for interview {}: {}",
                    interview.getId(), e.getMessage());
        }

        request.setStatus(ReschedRequestStatus.CONFIRMED);
        request.setConfirmedSlot(newTime);
        request.setRespondedAt(LocalDateTime.now());
        InterviewReschedRequest saved = requestRepo.save(request);

        log.info("Reschedule request {} CONFIRMED — interview {} moved to {}",
                requestId, interview.getId(), newTime);
        publishEvent("INTERVIEW_RESCHEDULE_CONFIRMED", saved, interview);
        return toResponse(saved);
    }

    // ── Decline ─────────────────────────────────────────────────────────────
    @Transactional
    public ReschedRequestResponse decline(UUID requestId, UUID requesterId) {
        InterviewReschedRequest request = findRequest(requestId);
        if (request.getStatus() != ReschedRequestStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Already responded.");
        }
        Interview interview = findInterview(request.getInterviewId());
        boolean isCandidate = interview.getCandidateId().equals(requesterId);
        boolean isRecruiter = interview.getRecruiterId().equals(requesterId);
        if (!isCandidate && !isRecruiter) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the interview's participants can respond.");
        }
        // Only the OTHER party declines. The proposer cancels via /cancel.
        if (request.getProposedBy() == InterviewReschedRequest.ProposedBy.RECRUITER && !isCandidate) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The recruiter who proposed should cancel, not decline.");
        }
        if (request.getProposedBy() == InterviewReschedRequest.ProposedBy.CANDIDATE && !isRecruiter) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The candidate who proposed should cancel, not decline.");
        }
        request.setStatus(ReschedRequestStatus.DECLINED);
        request.setRespondedAt(LocalDateTime.now());
        InterviewReschedRequest saved = requestRepo.save(request);
        publishEvent("INTERVIEW_RESCHEDULE_DECLINED", saved, interview);
        return toResponse(saved);
    }

    // ── Cancel (proposer pulls back) ────────────────────────────────────────
    @Transactional
    public ReschedRequestResponse cancel(UUID requestId, UUID requesterId, boolean admin) {
        InterviewReschedRequest request = findRequest(requestId);
        if (request.getStatus() != ReschedRequestStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Already responded.");
        }
        if (!admin && !request.getRequesterId().equals(requesterId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the proposer can cancel a reschedule request.");
        }
        request.setStatus(ReschedRequestStatus.CANCELLED);
        request.setRespondedAt(LocalDateTime.now());
        InterviewReschedRequest saved = requestRepo.save(request);
        Interview interview = findInterview(request.getInterviewId());
        publishEvent("INTERVIEW_RESCHEDULE_CANCELLED", saved, interview);
        return toResponse(saved);
    }

    // ── Auto-expire ─────────────────────────────────────────────────────────
    @Transactional
    public int expirePending(LocalDateTime now) {
        List<InterviewReschedRequest> pending = requestRepo.findByStatus(ReschedRequestStatus.PENDING);
        int n = 0;
        for (InterviewReschedRequest r : pending) {
            if (r.getDeadline() != null && now.isAfter(r.getDeadline())) {
                r.setStatus(ReschedRequestStatus.EXPIRED);
                r.setRespondedAt(now);
                requestRepo.save(r);
                n++;
            }
        }
        return n;
    }

    // ── Reads ───────────────────────────────────────────────────────────────
    public List<ReschedRequestResponse> getByInterview(UUID interviewId) {
        return requestRepo.findByInterviewId(interviewId).stream()
                .map(this::toResponse).collect(Collectors.toList());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────
    private Interview findInterview(UUID id) {
        return interviewRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Interview not found: " + id));
    }

    private InterviewReschedRequest findRequest(UUID id) {
        return requestRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Reschedule request not found: " + id));
    }

    private void publishEvent(String type, InterviewReschedRequest r, Interview interview) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("requestId", r.getId().toString());
            payload.put("interviewId", interview.getId().toString());
            payload.put("applicationId", interview.getApplicationId().toString());
            payload.put("recruiterId", interview.getRecruiterId().toString());
            payload.put("candidateId", interview.getCandidateId().toString());
            payload.put("proposedBy", r.getProposedBy().name());
            payload.put("jobTitle", interview.getJobTitle() != null
                    ? interview.getJobTitle() : "an interview");
            if (r.getConfirmedSlot() != null) {
                payload.put("confirmedSlot", r.getConfirmedSlot().toString());
            }
            AppEventMessage evt = new AppEventMessage();
            evt.setEventType(type);
            evt.setProducer("interview-service");
            evt.setPayload(payload);
            eventPublisher.publish("notify.interview", evt);
        } catch (Exception e) {
            log.warn("Could not publish {} event: {}", type, e.getMessage());
        }
    }

    private ReschedRequestResponse toResponse(InterviewReschedRequest r) {
        return ReschedRequestResponse.builder()
                .id(r.getId())
                .interviewId(r.getInterviewId())
                .proposedBy(r.getProposedBy())
                .requesterId(r.getRequesterId())
                .proposedSlots(r.getProposedSlots())
                .deadline(r.getDeadline())
                .status(r.getStatus())
                .confirmedSlot(r.getConfirmedSlot())
                .message(r.getMessage())
                .createdAt(r.getCreatedAt())
                .respondedAt(r.getRespondedAt())
                .build();
    }
}
