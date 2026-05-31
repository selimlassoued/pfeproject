package com.zaina.interviewservice.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaina.interviewservice.dto.CreateDelegationRequest;
import com.zaina.interviewservice.dto.DelegationResponse;
import com.zaina.interviewservice.services.InterviewDelegationService;
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
public class InterviewDelegationController {

    private final InterviewDelegationService service;

    @PostMapping("/{interviewId}/delegate")
    public ResponseEntity<DelegationResponse> propose(
            @PathVariable UUID interviewId,
            @RequestBody CreateDelegationRequest req,
            HttpServletRequest httpRequest) {
        UUID from = extractUserId(httpRequest);
        return ResponseEntity.ok(service.propose(interviewId, req, from));
    }

    @GetMapping("/{interviewId}/delegations")
    public ResponseEntity<List<DelegationResponse>> byInterview(@PathVariable UUID interviewId) {
        return ResponseEntity.ok(service.getByInterview(interviewId));
    }

    @GetMapping("/delegations/incoming")
    public ResponseEntity<List<DelegationResponse>> incoming(HttpServletRequest httpRequest) {
        UUID me = extractUserId(httpRequest);
        return ResponseEntity.ok(service.getIncoming(me));
    }

    @GetMapping("/delegations/outgoing")
    public ResponseEntity<List<DelegationResponse>> outgoing(HttpServletRequest httpRequest) {
        UUID me = extractUserId(httpRequest);
        return ResponseEntity.ok(service.getOutgoing(me));
    }

    @PostMapping("/delegations/{id}/accept")
    public ResponseEntity<DelegationResponse> accept(
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        UUID me = extractUserId(httpRequest);
        return ResponseEntity.ok(service.accept(id, me));
    }

    @PostMapping("/delegations/{id}/decline")
    public ResponseEntity<DelegationResponse> decline(
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        UUID me = extractUserId(httpRequest);
        return ResponseEntity.ok(service.decline(id, me));
    }

    @PostMapping("/delegations/{id}/cancel")
    public ResponseEntity<DelegationResponse> cancel(
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        UUID me = extractUserId(httpRequest);
        boolean admin = hasRole(httpRequest, "ADMIN") || hasRole(httpRequest, "SUPERADMIN");
        return ResponseEntity.ok(service.cancel(id, me, admin));
    }

    // ── JWT helpers ─────────────────────────────────────────────────────────
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

    @SuppressWarnings("unchecked")
    private boolean hasRole(HttpServletRequest req, String roleName) {
        try {
            String auth = req.getHeader("Authorization");
            if (auth == null || !auth.startsWith("Bearer ")) return false;
            String payload = auth.substring(7).split("\\.")[1];
            String decoded = new String(Base64.getUrlDecoder().decode(payload));
            Map<String, Object> claims = new ObjectMapper().readValue(decoded, Map.class);
            Map<String, Object> realm = (Map<String, Object>) claims.get("realm_access");
            if (realm == null) return false;
            Object roles = realm.get("roles");
            return roles instanceof List<?> list && list.contains(roleName);
        } catch (Exception e) {
            return false;
        }
    }
}
