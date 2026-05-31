package com.zaina.interviewservice.services;

import com.zaina.interviewservice.clients.ApplicationClient;
import com.zaina.interviewservice.dto.ApplicationStatusUpdateRequest;
import com.zaina.interviewservice.dto.CreateProposalRequest;
import com.zaina.interviewservice.dto.InterviewResponse;
import com.zaina.interviewservice.dto.ProposalResponse;
import com.zaina.interviewservice.dto.ScheduleInterviewRequest;
import com.zaina.interviewservice.entities.InterviewProposal;
import com.zaina.interviewservice.entities.InterviewProposalStatus;
import com.zaina.interviewservice.exceptions.ConflictException;
import com.zaina.interviewservice.messaging.AppEventMessage;
import com.zaina.interviewservice.messaging.InterviewEventPublisher;
import com.zaina.interviewservice.repos.InterviewProposalRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Recruiter proposes 2-4 slots, candidate picks one. On pick we delegate to
 * {@link InterviewService#scheduleInterview} so the existing pipeline (Jitsi
 * room, Google Calendar invite, CV context, application status transition,
 * email) runs unchanged — proposals don't replace direct scheduling, they
 * sit in front of it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewProposalService {

    // Min 1 so a recruiter with tight availability can make a single "this is
    // the only slot I have" offer (ATS-style). Candidate still picks or declines.
    private static final int MIN_SLOTS = 1;
    private static final int MAX_SLOTS = 4;

    private final InterviewProposalRepo proposalRepo;
    private final InterviewService interviewService;
    private final InterviewEventPublisher eventPublisher;
    private final ApplicationClient applicationClient;

    @Transactional
    public ProposalResponse createProposal(CreateProposalRequest req) {
        // ── Validate slots ───────────────────────────────────────────────
        List<LocalDateTime> slots = req.getProposedSlots();
        if (slots == null || slots.size() < MIN_SLOTS || slots.size() > MAX_SLOTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A proposal needs between " + MIN_SLOTS + " and " + MAX_SLOTS + " slots.");
        }
        LocalDateTime now = LocalDateTime.now();
        if (slots.stream().anyMatch(s -> s == null || s.isBefore(now))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "All proposed slots must be in the future.");
        }
        // De-dup on the same minute — recruiter clicking the same slot twice
        // is almost always an accident, and the candidate UI would render
        // two identical buttons.
        long distinct = slots.stream().distinct().count();
        if (distinct != slots.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Proposed slots must be distinct.");
        }
        if (req.getDeadline() == null || req.getDeadline().isBefore(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Deadline must be in the future.");
        }

        // ── No active proposal or interview already on this application ──
        boolean hasActiveProposal = proposalRepo
                .findByApplicationId(req.getApplicationId())
                .stream()
                .anyMatch(p -> p.getStatus() == InterviewProposalStatus.PENDING);
        if (hasActiveProposal) {
            throw new ConflictException(
                    "There's already a pending interview proposal for this application. " +
                            "Cancel it before sending a new one.");
        }

        InterviewProposal proposal = InterviewProposal.builder()
                .applicationId(req.getApplicationId())
                .jobId(req.getJobId())
                .recruiterId(req.getRecruiterId())
                .candidateId(req.getCandidateId())
                .candidateEmail(req.getCandidateEmail())
                .recruiterEmail(req.getRecruiterEmail())
                .candidateName(req.getCandidateName())
                .recruiterName(req.getRecruiterName())
                .jobTitle(req.getJobTitle())
                .proposedSlots(new ArrayList<>(slots))
                .deadline(req.getDeadline())
                .message(req.getMessage())
                .status(InterviewProposalStatus.PENDING)
                .build();

        InterviewProposal saved = proposalRepo.save(proposal);
        log.info("Proposal {} created for application {} ({} slots, deadline {})",
                saved.getId(), saved.getApplicationId(), slots.size(), saved.getDeadline());

        // Move the application to INTERVIEW_PHASE so the pipeline reflects the
        // recruiter's decision to interview — same as direct scheduling does.
        // Wrapped in try/catch because the app may already be in INTERVIEW_PHASE
        // (recruiter sending a second proposal after the first was cancelled);
        // the transition validator on the application side rejects self-edits
        // and that's fine — log and move on.
        try {
            applicationClient.updateStatus(saved.getApplicationId(),
                    new ApplicationStatusUpdateRequest("INTERVIEW_PHASE"));
            log.info("Application {} moved to INTERVIEW_PHASE on proposal send",
                    saved.getApplicationId());
        } catch (Exception e) {
            log.warn("Could not move application {} to INTERVIEW_PHASE: {}",
                    saved.getApplicationId(), e.getMessage());
        }

        publishProposalEvent("INTERVIEW_PROPOSAL_SENT", saved);
        return toResponse(saved);
    }

    @Transactional
    public ProposalResponse pickSlot(UUID proposalId, int slotIndex, UUID candidateId) {
        InterviewProposal proposal = findById(proposalId);

        if (proposal.getStatus() != InterviewProposalStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This proposal is " + proposal.getStatus().name().toLowerCase()
                            + " — nothing to pick.");
        }
        // Only the proposal's candidate can pick — admins not allowed here;
        // if the candidate is unreachable the recruiter should cancel and
        // re-issue or schedule directly.
        if (candidateId != null && !candidateId.equals(proposal.getCandidateId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the invited candidate can pick a slot.");
        }
        if (proposal.getDeadline().isBefore(LocalDateTime.now())) {
            proposal.setStatus(InterviewProposalStatus.EXPIRED);
            proposalRepo.save(proposal);
            throw new ResponseStatusException(HttpStatus.GONE,
                    "This proposal has expired — please ask the recruiter for new times.");
        }
        if (slotIndex < 0 || slotIndex >= proposal.getProposedSlots().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "slotIndex is out of range.");
        }

        LocalDateTime chosen = proposal.getProposedSlots().get(slotIndex);
        if (chosen.isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That slot has already passed — please pick another.");
        }

        // ── Delegate to the existing scheduling pipeline. This re-runs the
        //    clash check (recruiter / candidate can't be double-booked),
        //    creates the Jitsi room, fetches CV context, syncs Google
        //    Calendar, moves the application to INTERVIEW_PHASE, and sends
        //    notifications — so we get all of it for free. ──────────────
        ScheduleInterviewRequest schedReq = new ScheduleInterviewRequest();
        schedReq.setApplicationId(proposal.getApplicationId());
        schedReq.setJobId(proposal.getJobId());
        schedReq.setRecruiterId(proposal.getRecruiterId());
        schedReq.setCandidateId(proposal.getCandidateId());
        schedReq.setCandidateEmail(proposal.getCandidateEmail());
        schedReq.setRecruiterEmail(proposal.getRecruiterEmail());
        schedReq.setCandidateName(proposal.getCandidateName());
        schedReq.setRecruiterName(proposal.getRecruiterName());
        schedReq.setJobTitle(proposal.getJobTitle());
        schedReq.setScheduledAt(chosen);
        schedReq.setRecordingConsent(false);

        InterviewResponse interview = interviewService.scheduleInterview(schedReq);

        proposal.setStatus(InterviewProposalStatus.CONFIRMED);
        proposal.setConfirmedSlot(chosen);
        proposal.setInterviewId(interview.getId());
        proposal.setRespondedAt(LocalDateTime.now());
        InterviewProposal saved = proposalRepo.save(proposal);

        log.info("Proposal {} confirmed — slot index {} ({}) — interview {}",
                proposalId, slotIndex, chosen, interview.getId());

        publishProposalEvent("INTERVIEW_PROPOSAL_PICKED", saved);
        return toResponse(saved);
    }

    @Transactional
    public ProposalResponse cancelProposal(UUID proposalId, UUID requesterId, boolean admin) {
        InterviewProposal proposal = findById(proposalId);
        if (!admin && (requesterId == null
                || !requesterId.equals(proposal.getRecruiterId()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the recruiter who created this proposal can cancel it.");
        }
        if (proposal.getStatus() != InterviewProposalStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only pending proposals can be cancelled.");
        }
        proposal.setStatus(InterviewProposalStatus.CANCELLED);
        proposal.setRespondedAt(LocalDateTime.now());
        InterviewProposal saved = proposalRepo.save(proposal);

        publishProposalEvent("INTERVIEW_PROPOSAL_CANCELLED", saved);
        return toResponse(saved);
    }

    /**
     * Candidate can't make any of the offered slots. Marks the proposal
     * DECLINED and notifies the recruiter so they can send fresh times. The
     * recruiter stays in control of which times get offered — we don't let the
     * candidate propose arbitrary slots the recruiter may not be free for.
     */
    @Transactional
    public ProposalResponse declineProposal(UUID proposalId, UUID candidateId, String reason) {
        InterviewProposal proposal = findById(proposalId);
        if (proposal.getStatus() != InterviewProposalStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This proposal is " + proposal.getStatus().name().toLowerCase()
                            + " — nothing to decline.");
        }
        if (candidateId != null && !candidateId.equals(proposal.getCandidateId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the invited candidate can decline this proposal.");
        }
        proposal.setStatus(InterviewProposalStatus.DECLINED);
        proposal.setRespondedAt(LocalDateTime.now());
        if (reason != null && !reason.isBlank()) proposal.setDeclineReason(reason.trim());
        InterviewProposal saved = proposalRepo.save(proposal);

        Map<String, Object> extra = new HashMap<>();
        if (reason != null && !reason.isBlank()) extra.put("reason", reason.trim());
        publishProposalEvent("INTERVIEW_PROPOSAL_DECLINED", saved, extra);

        log.info("Proposal {} declined by candidate {}", proposalId, candidateId);
        return toResponse(saved);
    }

    public ProposalResponse getProposal(UUID proposalId) {
        return toResponse(findById(proposalId));
    }

    public List<ProposalResponse> getByApplication(UUID applicationId) {
        return proposalRepo.findByApplicationId(applicationId).stream()
                .map(this::toResponse).collect(Collectors.toList());
    }

    public List<ProposalResponse> getByCandidate(UUID candidateId) {
        return proposalRepo.findByCandidateId(candidateId).stream()
                .map(this::toResponse).collect(Collectors.toList());
    }

    public List<ProposalResponse> getByRecruiter(UUID recruiterId) {
        return proposalRepo.findByRecruiterId(recruiterId).stream()
                .map(this::toResponse).collect(Collectors.toList());
    }

    private InterviewProposal findById(UUID id) {
        return proposalRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Proposal not found: " + id));
    }

    private void publishProposalEvent(String type, InterviewProposal proposal) {
        publishProposalEvent(type, proposal, null);
    }

    private void publishProposalEvent(String type, InterviewProposal proposal,
                                      Map<String, Object> extra) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("proposalId", proposal.getId().toString());
            payload.put("applicationId", proposal.getApplicationId().toString());
            payload.put("candidateId", proposal.getCandidateId().toString());
            payload.put("recruiterId", proposal.getRecruiterId().toString());
            payload.put("jobTitle", proposal.getJobTitle() != null
                    ? proposal.getJobTitle() : "an interview");
            if (proposal.getConfirmedSlot() != null) {
                payload.put("confirmedSlot", proposal.getConfirmedSlot().toString());
            }
            if (proposal.getInterviewId() != null) {
                payload.put("interviewId", proposal.getInterviewId().toString());
            }
            if (extra != null) payload.putAll(extra);
            AppEventMessage evt = new AppEventMessage();
            evt.setEventType(type);
            evt.setProducer("interview-service");
            evt.setPayload(payload);
            eventPublisher.publish("notify.interview", evt);
        } catch (Exception e) {
            log.warn("Could not publish {} event for proposal {}: {}",
                    type, proposal.getId(), e.getMessage());
        }
    }

    private ProposalResponse toResponse(InterviewProposal p) {
        return ProposalResponse.builder()
                .id(p.getId())
                .applicationId(p.getApplicationId())
                .jobId(p.getJobId())
                .recruiterId(p.getRecruiterId())
                .candidateId(p.getCandidateId())
                .candidateEmail(p.getCandidateEmail())
                .recruiterEmail(p.getRecruiterEmail())
                .candidateName(p.getCandidateName())
                .recruiterName(p.getRecruiterName())
                .jobTitle(p.getJobTitle())
                .proposedSlots(p.getProposedSlots())
                .deadline(p.getDeadline())
                .status(p.getStatus())
                .confirmedSlot(p.getConfirmedSlot())
                .interviewId(p.getInterviewId())
                .message(p.getMessage())
                .declineReason(p.getDeclineReason())
                .createdAt(p.getCreatedAt())
                .respondedAt(p.getRespondedAt())
                .build();
    }
}
