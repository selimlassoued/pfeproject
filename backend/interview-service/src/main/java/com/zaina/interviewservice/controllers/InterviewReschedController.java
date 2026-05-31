package com.zaina.interviewservice.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaina.interviewservice.dto.CreateReschedRequest;
import com.zaina.interviewservice.dto.ReschedRequestResponse;
import com.zaina.interviewservice.entities.InterviewReschedRequest;
import com.zaina.interviewservice.services.InterviewReschedService;
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
@RequestMapping("/api/interviews")
@RequiredArgsConstructor
@Slf4j
public class InterviewReschedController {

    private final InterviewReschedService reschedService;

    /** Either side proposes new times. Role is inferred from JWT roles. */
    @PostMapping("/{interviewId}/reschedule")
    public ResponseEntity<ReschedRequestResponse> propose(
            @PathVariable UUID interviewId,
            @RequestBody CreateReschedRequest req,
            HttpServletRequest httpRequest) {
        JwtInfo info = extractJwt(httpRequest);
        InterviewReschedRequest.ProposedBy role = info.hasRole("CANDIDATE")
                ? InterviewReschedRequest.ProposedBy.CANDIDATE
                : InterviewReschedRequest.ProposedBy.RECRUITER;
        return ResponseEntity.ok(reschedService.propose(interviewId, req, info.userId, role));
    }

    /** All reschedule requests for an interview (most recent first). Used by both sides. */
    @GetMapping("/{interviewId}/reschedule")
    public ResponseEntity<List<ReschedRequestResponse>> list(@PathVariable UUID interviewId) {
        return ResponseEntity.ok(reschedService.getByInterview(interviewId));
    }

    /** Recipient picks one of the proposed slots. */
    @PostMapping("/reschedule/{requestId}/accept")
    public ResponseEntity<ReschedRequestResponse> accept(
            @PathVariable UUID requestId,
            @RequestParam int slotIndex,
            HttpServletRequest httpRequest) {
        JwtInfo info = extractJwt(httpRequest);
        return ResponseEntity.ok(reschedService.accept(requestId, slotIndex, info.userId));
    }

    /** Recipient says no — original time stands. */
    @PostMapping("/reschedule/{requestId}/decline")
    public ResponseEntity<ReschedRequestResponse> decline(
            @PathVariable UUID requestId,
            HttpServletRequest httpRequest) {
        JwtInfo info = extractJwt(httpRequest);
        return ResponseEntity.ok(reschedService.decline(requestId, info.userId));
    }

    /** Proposer withdraws their request. */
    @PostMapping("/reschedule/{requestId}/cancel")
    public ResponseEntity<ReschedRequestResponse> cancel(
            @PathVariable UUID requestId,
            HttpServletRequest httpRequest) {
        JwtInfo info = extractJwt(httpRequest);
        boolean admin = info.hasRole("ADMIN") || info.hasRole("SUPERADMIN");
        return ResponseEntity.ok(reschedService.cancel(requestId, info.userId, admin));
    }

    // ── JWT extraction (same pattern as InterviewProposalController) ────────
    private record JwtInfo(UUID userId, List<String> roles) {
        boolean hasRole(String r) { return roles != null && roles.contains(r); }
    }

    @SuppressWarnings("unchecked")
    private JwtInfo extractJwt(HttpServletRequest req) {
        try {
            String auth = req.getHeader("Authorization");
            if (auth == null || !auth.startsWith("Bearer ")) return new JwtInfo(null, List.of());
            String payload = auth.substring(7).split("\\.")[1];
            String decoded = new String(Base64.getUrlDecoder().decode(payload));
            Map<String, Object> claims = new ObjectMapper().readValue(decoded, Map.class);
            UUID uid = claims.get("sub") == null ? null : UUID.fromString(claims.get("sub").toString());
            Map<String, Object> realm = (Map<String, Object>) claims.get("realm_access");
            List<String> roles = realm != null && realm.get("roles") instanceof List<?> r
                    ? r.stream().map(Object::toString).toList()
                    : List.of();
            return new JwtInfo(uid, roles);
        } catch (Exception e) {
            log.warn("Could not extract JWT: {}", e.getMessage());
            return new JwtInfo(null, List.of());
        }
    }
}
