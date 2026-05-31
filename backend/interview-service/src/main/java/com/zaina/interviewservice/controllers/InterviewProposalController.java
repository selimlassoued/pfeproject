package com.zaina.interviewservice.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaina.interviewservice.clients.UserClient;
import com.zaina.interviewservice.dto.CreateProposalRequest;
import com.zaina.interviewservice.dto.ProposalResponse;
import com.zaina.interviewservice.services.InterviewProposalService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
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
            @RequestBody CreateProposalRequest req,
            HttpServletRequest httpRequest) {

        // Recruiter identity from JWT — same pattern as InterviewController.
        String auth = httpRequest.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            try {
                String token = auth.substring(7);
                String payload = token.split("\\.")[1];
                String decoded = new String(Base64.getUrlDecoder().decode(payload));
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> claims = mapper.readValue(decoded, Map.class);
                if (claims.get("name") != null)
                    req.setRecruiterName(claims.get("name").toString());
                if (claims.get("email") != null)
                    req.setRecruiterEmail(claims.get("email").toString());
                if (claims.get("sub") != null && req.getRecruiterId() == null)
                    req.setRecruiterId(UUID.fromString(claims.get("sub").toString()));
            } catch (Exception e) {
                log.warn("Could not extract recruiter info from JWT: {}", e.getMessage());
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
            HttpServletRequest httpRequest) {

        UUID candidateId = extractUserId(httpRequest);
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
            HttpServletRequest httpRequest) {
        UUID candidateId = extractUserId(httpRequest);
        String reason = body != null ? body.get("reason") : null;
        return ResponseEntity.ok(proposalService.declineProposal(id, candidateId, reason));
    }

    /** Pull the caller's userId out of the JWT (sub claim). Returns null on failure. */
    private UUID extractUserId(HttpServletRequest req) {
        try {
            String auth = req.getHeader("Authorization");
            if (auth == null || !auth.startsWith("Bearer ")) return null;
            String payload = auth.substring(7).split("\\.")[1];
            String decoded = new String(Base64.getUrlDecoder().decode(payload));
            Map<?, ?> claims = new ObjectMapper().readValue(decoded, Map.class);
            Object sub = claims.get("sub");
            return sub == null ? null : UUID.fromString(sub.toString());
        } catch (Exception e) {
            log.warn("Could not extract userId from JWT: {}", e.getMessage());
            return null;
        }
    }
}
