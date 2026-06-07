package com.zaina.interviewservice.controllers;

import com.zaina.interviewservice.clients.UserClient;
import com.zaina.interviewservice.dto.CreateProposalRequest;
import com.zaina.interviewservice.dto.ProposalResponse;
import com.zaina.interviewservice.services.InterviewProposalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/interviews/proposals")
@RequiredArgsConstructor
@Slf4j
public class InterviewProposalController {

    private final InterviewProposalService proposalService;
    private final UserClient userClient;

    @PostMapping
    public ResponseEntity<ProposalResponse> create(
            @jakarta.validation.Valid @RequestBody CreateProposalRequest req,
            @AuthenticationPrincipal Jwt jwt) {

        // Recruiter identity from the JWT that Spring already validated.
        // Previously this controller hand-decoded the Bearer payload,
        // trusting it without verifying the signature.
        if (jwt != null) {
            String name = jwt.getClaimAsString("name");
            if (name != null && !name.isBlank()) req.setRecruiterName(name);
            String email = jwt.getClaimAsString("email");
            if (email != null && !email.isBlank()) req.setRecruiterEmail(email);
            String sub = jwt.getSubject();
            if (sub != null && req.getRecruiterId() == null) {
                try {
                    req.setRecruiterId(UUID.fromString(sub));
                } catch (IllegalArgumentException ex) {
                    log.warn("JWT sub is not a UUID: {}", sub);
                }
            }
        }

        // Fetch authoritative candidate name + email so the proposal record
        // doesn't carry stale values entered by the recruiter.
        try {
            UserClient.UserProfile profile = userClient.getUserProfile(req.getCandidateId());
            if (profile.fullName() != null && !profile.fullName().isBlank())
                req.setCandidateName(profile.fullName());
            if (profile.email() != null && !profile.email().isBlank())
                req.setCandidateEmail(profile.email());
        } catch (Exception e) {
            log.warn("Could not fetch candidate profile for {}: {}",
                    req.getCandidateId(), e.getMessage());
        }

        return ResponseEntity.ok(proposalService.createProposal(req));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProposalResponse> getOne(@PathVariable UUID id) {
        return ResponseEntity.ok(proposalService.getProposal(id));
    }

    @GetMapping("/application/{applicationId}")
    public ResponseEntity<List<ProposalResponse>> getByApplication(@PathVariable UUID applicationId) {
        return ResponseEntity.ok(proposalService.getByApplication(applicationId));
    }

    @GetMapping("/candidate/{candidateId}")
    public ResponseEntity<List<ProposalResponse>> getByCandidate(@PathVariable UUID candidateId) {
        return ResponseEntity.ok(proposalService.getByCandidate(candidateId));
    }

    @GetMapping("/recruiter/{recruiterId}")
    public ResponseEntity<List<ProposalResponse>> getByRecruiter(@PathVariable UUID recruiterId) {
        return ResponseEntity.ok(proposalService.getByRecruiter(recruiterId));
    }

    @PostMapping("/{id}/pick")
    public ResponseEntity<ProposalResponse> pick(
            @PathVariable UUID id,
            @RequestParam int slotIndex,
            @AuthenticationPrincipal Jwt jwt) {

        UUID candidateId = subjectAsUuid(jwt);
        return ResponseEntity.ok(proposalService.pickSlot(id, slotIndex, candidateId));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ProposalResponse> cancel(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID requesterId,
            @RequestParam(defaultValue = "false") boolean admin) {
        return ResponseEntity.ok(proposalService.cancelProposal(id, requesterId, admin));
    }

    /** Candidate can't make any offered slot — decline so the recruiter re-proposes. */
    @PostMapping("/{id}/decline")
    public ResponseEntity<ProposalResponse> decline(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal Jwt jwt) {
        UUID candidateId = subjectAsUuid(jwt);
        String reason = body != null ? body.get("reason") : null;
        return ResponseEntity.ok(proposalService.declineProposal(id, candidateId, reason));
    }

    /** JWT 'sub' as UUID. Returns null if missing or not a UUID. */
    private static UUID subjectAsUuid(Jwt jwt) {
        if (jwt == null) return null;
        String sub = jwt.getSubject();
        if (sub == null) return null;
        try {
            return UUID.fromString(sub);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
