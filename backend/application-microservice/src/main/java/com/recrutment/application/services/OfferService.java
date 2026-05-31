package com.recrutment.application.services;

import com.recrutment.application.dto.CreateOfferRequest;
import com.recrutment.application.dto.CreateRevisionRequest;
import com.recrutment.application.dto.OfferDto;
import com.recrutment.application.dto.OfferRevisionDto;
import com.recrutment.application.entities.Application;
import com.recrutment.application.entities.Offer;
import com.recrutment.application.entities.OfferRevision;
import com.recrutment.application.enums.ApplicationStatus;
import com.recrutment.application.enums.OfferStatus;
import com.recrutment.application.messaging.AppEventMessage;
import com.recrutment.application.messaging.AppEventPublisher;
import com.recrutment.application.repos.ApplicationRepo;
import com.recrutment.application.repos.OfferRepo;
import com.recrutment.application.repos.OfferRevisionRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Job offers extended to candidates after they pass the interview stage. The
 * candidate and recruiter can revise terms back-and-forth (salary, start date,
 * contract type) until one side accepts or the offer expires.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OfferService {

    private static final String DEFAULT_CURRENCY = "TND";

    private final OfferRepo offerRepo;
    private final OfferRevisionRepo revisionRepo;
    private final ApplicationRepo applicationRepo;
    private final ApplicationService applicationService;
    private final AppEventPublisher eventPublisher;

    // ── Create ──────────────────────────────────────────────────────────────
    @Transactional
    public OfferDto createOffer(UUID applicationId, CreateOfferRequest req, UUID recruiterId) {
        Application app = applicationRepo.findById(applicationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Application not found: " + applicationId));

        // Only INTERVIEW_PHASE or OFFER (after a previous offer was withdrawn /
        // expired / declined) can have a new offer issued.
        if (app.getStatus() != ApplicationStatus.INTERVIEW_PHASE
                && app.getStatus() != ApplicationStatus.OFFER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Offers can only be sent from INTERVIEW_PHASE (current status: "
                            + app.getStatus() + ").");
        }

        // Only one active offer per application — block a second one if the
        // previous is still SENT / NEGOTIATING.
        offerRepo.findByApplicationId(applicationId).ifPresent(existing -> {
            if (existing.getStatus() == OfferStatus.SENT
                    || existing.getStatus() == OfferStatus.NEGOTIATING) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "An active offer already exists for this application. " +
                                "Withdraw it before sending a new one.");
            }
        });

        if (req.getExpiresAt() == null || req.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Expiry date must be in the future.");
        }

        String currency = (req.getCurrency() != null && !req.getCurrency().isBlank())
                ? req.getCurrency().toUpperCase() : DEFAULT_CURRENCY;

        // If a previous offer existed (declined / withdrawn / expired), replace
        // it in-place so the unique-per-application invariant holds.
        Offer offer = offerRepo.findByApplicationId(applicationId).orElseGet(Offer::new);
        offer.setApplicationId(applicationId);
        offer.setJobId(app.getJobId());
        offer.setRecruiterId(recruiterId);
        offer.setCandidateUserId(app.getCandidateUserId());
        offer.setSalary(req.getSalary());
        offer.setCurrency(currency);
        offer.setStartDate(req.getStartDate());
        offer.setContractType(req.getContractType());
        offer.setMessage(req.getMessage());
        offer.setStatus(OfferStatus.SENT);
        offer.setExpiresAt(req.getExpiresAt());
        offer.setRespondedAt(null);
        Offer saved = offerRepo.save(offer);

        // Wipe the previous revision history if we're recycling the row — a
        // brand-new offer shouldn't inherit the old back-and-forth.
        revisionRepo.findByOfferIdOrderByCreatedAtAsc(saved.getId())
                .forEach(revisionRepo::delete);

        revisionRepo.save(OfferRevision.builder()
                .offerId(saved.getId())
                .proposedBy(OfferRevision.ProposedBy.RECRUITER)
                .salary(saved.getSalary())
                .currency(saved.getCurrency())
                .startDate(saved.getStartDate())
                .contractType(saved.getContractType())
                .message(saved.getMessage())
                .build());

        // Move the application to OFFER. This triggers the quota guard already
        // built into ApplicationService.updateStatus.
        if (app.getStatus() != ApplicationStatus.OFFER) {
            applicationService.updateStatus(applicationId,
                    ApplicationStatus.OFFER, recruiterId.toString());
        }

        log.info("Offer {} created for application {} by recruiter {}",
                saved.getId(), applicationId, recruiterId);
        publishOfferEvent(saved);
        return toDto(saved);
    }

    // ── Revise ──────────────────────────────────────────────────────────────
    @Transactional
    public OfferDto postRevision(UUID applicationId, CreateRevisionRequest req,
                                 boolean recruiterSide, UUID requesterId) {
        Offer offer = requireActiveOffer(applicationId);

        // Decide which side this revision is from. A user can hold more than one
        // realm role (a recruiter often also carries the default CANDIDATE role),
        // so we trust a recruiter-type role first and only fall back to CANDIDATE
        // when the requester is actually the candidate on THIS offer. Any
        // recruiter can revise (admin/superadmin moderation included); we don't
        // lock to the original recruiter so a team handoff doesn't strand the
        // negotiation.
        OfferRevision.ProposedBy proposedBy;
        if (recruiterSide) {
            proposedBy = OfferRevision.ProposedBy.RECRUITER;
        } else if (offer.getCandidateUserId().equals(requesterId.toString())) {
            proposedBy = OfferRevision.ProposedBy.CANDIDATE;
        } else {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the offer's candidate or a recruiter can revise this offer.");
        }

        if (req.getSalary() != null) offer.setSalary(req.getSalary());
        if (req.getCurrency() != null && !req.getCurrency().isBlank())
            offer.setCurrency(req.getCurrency().toUpperCase());
        if (req.getStartDate() != null) offer.setStartDate(req.getStartDate());
        if (req.getContractType() != null) offer.setContractType(req.getContractType());
        offer.setStatus(OfferStatus.NEGOTIATING);
        Offer saved = offerRepo.save(offer);

        revisionRepo.save(OfferRevision.builder()
                .offerId(saved.getId())
                .proposedBy(proposedBy)
                .salary(saved.getSalary())
                .currency(saved.getCurrency())
                .startDate(saved.getStartDate())
                .contractType(saved.getContractType())
                .message(req.getMessage())
                .build());

        log.info("Offer {} revised by {} ({})", saved.getId(), proposedBy, requesterId);
        publishOfferEvent(saved);
        return toDto(saved);
    }

    // ── Accept ──────────────────────────────────────────────────────────────
    @Transactional
    public OfferDto acceptOffer(UUID applicationId, UUID candidateUserId) {
        Offer offer = requireActiveOffer(applicationId);
        if (!offer.getCandidateUserId().equals(candidateUserId.toString())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the candidate on this offer can accept it.");
        }
        offer.setStatus(OfferStatus.ACCEPTED);
        offer.setRespondedAt(Instant.now());
        Offer saved = offerRepo.save(offer);

        applicationService.updateStatus(applicationId,
                ApplicationStatus.HIRED, candidateUserId.toString());

        log.info("Offer {} accepted by candidate {} — application {} is HIRED",
                saved.getId(), candidateUserId, applicationId);
        publishOfferEvent(saved);
        return toDto(saved);
    }

    // ── Decline ─────────────────────────────────────────────────────────────
    @Transactional
    public OfferDto declineOffer(UUID applicationId, UUID candidateUserId, String reason) {
        Offer offer = requireActiveOffer(applicationId);
        if (!offer.getCandidateUserId().equals(candidateUserId.toString())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the candidate on this offer can decline it.");
        }
        offer.setStatus(OfferStatus.DECLINED);
        offer.setRespondedAt(Instant.now());
        Offer saved = offerRepo.save(offer);

        if (reason != null && !reason.isBlank()) {
            revisionRepo.save(OfferRevision.builder()
                    .offerId(saved.getId())
                    .proposedBy(OfferRevision.ProposedBy.CANDIDATE)
                    .salary(saved.getSalary())
                    .currency(saved.getCurrency())
                    .startDate(saved.getStartDate())
                    .contractType(saved.getContractType())
                    .message("[Declined] " + reason)
                    .build());
        }

        log.info("Offer {} declined by candidate {}", saved.getId(), candidateUserId);
        publishOfferEvent(saved);
        return toDto(saved);
    }

    // ── Withdraw (recruiter pulls back) ─────────────────────────────────────
    @Transactional
    public OfferDto withdrawOffer(UUID applicationId, UUID recruiterId, boolean admin) {
        Offer offer = requireActiveOffer(applicationId);
        if (!admin && !offer.getRecruiterId().equals(recruiterId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the recruiter who sent this offer can withdraw it.");
        }
        offer.setStatus(OfferStatus.WITHDRAWN);
        offer.setRespondedAt(Instant.now());
        Offer saved = offerRepo.save(offer);
        log.info("Offer {} withdrawn by recruiter {}", saved.getId(), recruiterId);
        publishOfferEvent(saved);
        return toDto(saved);
    }

    // ── Auto-expire ─────────────────────────────────────────────────────────
    @Transactional
    public int expirePending(Instant now) {
        List<Offer> active = offerRepo.findByStatusIn(
                List.of(OfferStatus.SENT, OfferStatus.NEGOTIATING));
        int expired = 0;
        for (Offer o : active) {
            if (o.getExpiresAt() != null && now.isAfter(o.getExpiresAt())) {
                o.setStatus(OfferStatus.EXPIRED);
                o.setRespondedAt(now);
                Offer saved = offerRepo.save(o);
                publishOfferEvent(saved);
                expired++;
                log.info("Offer {} auto-expired (expiresAt={})", o.getId(), o.getExpiresAt());
            }
        }
        return expired;
    }

    // ── Reads ───────────────────────────────────────────────────────────────
    public Optional<OfferDto> getByApplication(UUID applicationId) {
        return offerRepo.findByApplicationId(applicationId).map(this::toDto);
    }

    public List<OfferDto> getByCandidate(String candidateUserId) {
        return offerRepo.findByCandidateUserId(candidateUserId).stream().map(this::toDto).toList();
    }

    public List<OfferDto> getByRecruiter(UUID recruiterId) {
        return offerRepo.findByRecruiterId(recruiterId).stream().map(this::toDto).toList();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────
    private Offer requireActiveOffer(UUID applicationId) {
        Offer offer = offerRepo.findByApplicationId(applicationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No offer for application " + applicationId));
        if (offer.getStatus() != OfferStatus.SENT
                && offer.getStatus() != OfferStatus.NEGOTIATING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This offer is " + offer.getStatus().name().toLowerCase()
                            + " and can no longer be changed.");
        }
        if (offer.getExpiresAt() != null && offer.getExpiresAt().isBefore(Instant.now())) {
            offer.setStatus(OfferStatus.EXPIRED);
            offerRepo.save(offer);
            throw new ResponseStatusException(HttpStatus.GONE,
                    "This offer has expired.");
        }
        return offer;
    }

    /**
     * Fire a "this offer changed, wake the watchers" event so the candidate and
     * recruiter UIs reload in real time without a page refresh. Carries the
     * offer's current status and user ids so the notification-microservice can
     * route a STOMP ping to each affected user.
     */
    private void publishOfferEvent(Offer o) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("offerId", o.getId().toString());
            payload.put("applicationId", o.getApplicationId().toString());
            payload.put("recruiterId", o.getRecruiterId().toString());
            payload.put("candidateUserId", o.getCandidateUserId());
            payload.put("status", o.getStatus().name());
            AppEventMessage evt = new AppEventMessage();
            evt.setEventType("OFFER_CHANGED");
            evt.setProducer("application-microservice");
            evt.setPayload(payload);
            eventPublisher.publish("notify.offer", evt);
        } catch (Exception e) {
            log.warn("Could not publish OFFER_CHANGED event for offer {}: {}",
                    o.getId(), e.getMessage());
        }
    }

    private OfferDto toDto(Offer o) {
        List<OfferRevisionDto> revisions = revisionRepo
                .findByOfferIdOrderByCreatedAtAsc(o.getId()).stream()
                .map(this::revisionToDto)
                .toList();
        return OfferDto.builder()
                .id(o.getId())
                .applicationId(o.getApplicationId())
                .jobId(o.getJobId())
                .recruiterId(o.getRecruiterId())
                .candidateUserId(o.getCandidateUserId())
                .salary(o.getSalary())
                .currency(o.getCurrency())
                .startDate(o.getStartDate())
                .contractType(o.getContractType())
                .message(o.getMessage())
                .status(o.getStatus())
                .expiresAt(o.getExpiresAt())
                .createdAt(o.getCreatedAt())
                .respondedAt(o.getRespondedAt())
                .revisions(revisions)
                .build();
    }

    private OfferRevisionDto revisionToDto(OfferRevision r) {
        return OfferRevisionDto.builder()
                .id(r.getId())
                .offerId(r.getOfferId())
                .proposedBy(r.getProposedBy())
                .salary(r.getSalary())
                .currency(r.getCurrency())
                .startDate(r.getStartDate())
                .contractType(r.getContractType())
                .message(r.getMessage())
                .createdAt(r.getCreatedAt())
                .build();
    }
}
