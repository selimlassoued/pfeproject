package com.recrutment.application.restControllers;

import com.recrutment.application.dto.CreateOfferRequest;
import com.recrutment.application.dto.CreateRevisionRequest;
import com.recrutment.application.dto.OfferDto;
import com.recrutment.application.services.OfferService;
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
@RequestMapping("/api/applications")
@RequiredArgsConstructor
@Slf4j
public class OfferController {

    private final OfferService offerService;

    @PostMapping("/{applicationId}/offer")
    public ResponseEntity<OfferDto> create(
            @PathVariable UUID applicationId,
            @RequestBody CreateOfferRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        UUID recruiterId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(offerService.createOffer(applicationId, req, recruiterId));
    }

    /**
     * Offer attached to an application — returns 200 OK with the offer, or
     * 200 OK with a null body when no offer exists yet. We deliberately avoid
     * 404 here because "no offer yet" is the normal state for most applications,
     * not an error — and 404s pollute the browser console for callers who
     * legitimately just want to check.
     */
    @GetMapping("/{applicationId}/offer")
    public ResponseEntity<OfferDto> get(@PathVariable UUID applicationId) {
        return ResponseEntity.ok(offerService.getByApplication(applicationId).orElse(null));
    }

    /** Candidate dashboard — all offers for the calling candidate. */
    @GetMapping("/me/offers")
    public ResponseEntity<List<OfferDto>> myOffers(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(offerService.getByCandidate(jwt.getSubject()));
    }

    /** Recruiter dashboard — all offers issued by the calling recruiter. */
    @GetMapping("/recruiter/{recruiterId}/offers")
    public ResponseEntity<List<OfferDto>> recruiterOffers(@PathVariable UUID recruiterId) {
        return ResponseEntity.ok(offerService.getByRecruiter(recruiterId));
    }

    /**
     * Post a revision to the negotiation. Whether it counts as a recruiter or
     * candidate revision is inferred from the JWT's roles (CANDIDATE role =>
     * CANDIDATE, anything else => RECRUITER).
     */
    @PostMapping("/{applicationId}/offer/revisions")
    public ResponseEntity<OfferDto> revise(
            @PathVariable UUID applicationId,
            @RequestBody CreateRevisionRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        UUID requesterId = UUID.fromString(jwt.getSubject());
        // A user can hold several realm roles at once (a recruiter often also
        // has the default CANDIDATE role), so decide by recruiter-type role
        // first. The service resolves the final side from the offer ownership.
        boolean recruiterSide = hasRole(jwt, "RECRUITER")
                || hasRole(jwt, "ADMIN") || hasRole(jwt, "SUPERADMIN");
        return ResponseEntity.ok(offerService.postRevision(applicationId, req, recruiterSide, requesterId));
    }

    @PostMapping("/{applicationId}/offer/accept")
    public ResponseEntity<OfferDto> accept(
            @PathVariable UUID applicationId,
            @AuthenticationPrincipal Jwt jwt) {
        UUID candidateId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(offerService.acceptOffer(applicationId, candidateId));
    }

    @PostMapping("/{applicationId}/offer/decline")
    public ResponseEntity<OfferDto> decline(
            @PathVariable UUID applicationId,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal Jwt jwt) {
        UUID candidateId = UUID.fromString(jwt.getSubject());
        String reason = body != null ? body.get("reason") : null;
        return ResponseEntity.ok(offerService.declineOffer(applicationId, candidateId, reason));
    }

    @PostMapping("/{applicationId}/offer/withdraw")
    public ResponseEntity<OfferDto> withdraw(
            @PathVariable UUID applicationId,
            @AuthenticationPrincipal Jwt jwt) {
        UUID recruiterId = UUID.fromString(jwt.getSubject());
        boolean admin = hasRole(jwt, "ADMIN") || hasRole(jwt, "SUPERADMIN");
        return ResponseEntity.ok(offerService.withdrawOffer(applicationId, recruiterId, admin));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private boolean hasRole(Jwt jwt, String roleName) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null) return false;
        Object rolesObj = realmAccess.get("roles");
        if (!(rolesObj instanceof List<?> roles)) return false;
        return roles.contains(roleName);
    }
}
