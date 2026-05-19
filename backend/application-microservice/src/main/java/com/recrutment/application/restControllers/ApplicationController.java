package com.recrutment.application.restControllers;

import com.recrutment.application.dto.ApplicationDto;
import com.recrutment.application.dto.CvSummaryDto;
import com.recrutment.application.dto.PageResponse;
import com.recrutment.application.dto.SemanticMatchDto;
import com.recrutment.application.entities.Application;
import com.recrutment.application.enums.ApplicationStatus;
import com.recrutment.application.repos.ApplicationRepo;
import com.recrutment.application.repos.CvAnalysisRepo;
import com.recrutment.application.services.ApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.recrutment.application.entities.CvAnalysis;
import com.recrutment.application.services.CvAnalysisService;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/applications")
@RequiredArgsConstructor
public class ApplicationController {
    private final CvAnalysisService cvAnalysisService;
    private final ApplicationService service;
    private final ApplicationRepo repo;
    private final CvAnalysisRepo cvAnalysisRepository;

    // ── Application endpoints ─────────────────────────────────────────────────

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApplicationDto apply(
            @RequestParam UUID jobId,
            @RequestParam(required = false) String githubUrl,
            @RequestPart("cv") MultipartFile cv,
            @AuthenticationPrincipal Jwt jwt
    ) throws IOException {
        return service.apply(jobId, jwt.getSubject(), githubUrl, cv);
    }

    @GetMapping("/{id}/cv")
    public ResponseEntity<byte[]> downloadCv(@PathVariable UUID id) {
        Application app = repo.findById(id)
                .orElseThrow(() -> new RuntimeException("Application not found: " + id));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + app.getCvFileName() + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(app.getCvFile());
    }

    @GetMapping
    public List<ApplicationDto> list(
            @RequestParam(required = false) UUID applicationId,
            @RequestParam(required = false) UUID jobId,
            @RequestParam(required = false) ApplicationStatus status,
            @RequestParam(required = false) String jobTitle,
            @RequestParam(required = false) String candidateName
    ) {
        return service.listApplicationsPaged(applicationId, jobId, status, jobTitle, candidateName, 0, 50).getContent();
    }

    @GetMapping("/{id}")
    public ApplicationDto getOne(@PathVariable UUID id) {
        return service.getOne(id);
    }

    @GetMapping("/{id}/match")
    public ResponseEntity<SemanticMatchDto> getSemanticMatch(@PathVariable UUID id) {
        SemanticMatchDto match = service.getSemanticMatch(id);
        if (match == null) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).build();
        }
        return ResponseEntity.ok(match);
    }

    @GetMapping("/check-github")
    public boolean checkGithub(@RequestParam String url) {
        return service.checkGithubLink(url);
    }

    @GetMapping("/me/by-job/{jobId}")
    public ApplicationDto myApplicationByJob(
            @PathVariable UUID jobId,
            @AuthenticationPrincipal Jwt jwt) {
        return service.getMyApplicationByJob(jobId, jwt.getSubject());
    }

    @GetMapping("/me")
    public List<ApplicationDto> myApplications(@AuthenticationPrincipal Jwt jwt) {
        return service.listMyApplications(jwt.getSubject());
    }

    @GetMapping("/me/{id}")
    public ApplicationDto getMyOne(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.getMyApplication(id, jwt.getSubject());
    }

    @GetMapping("/me/{id}/cv")
    public ResponseEntity<byte[]> downloadMyCv(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        Application app = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Application not found: " + id));
        if (!jwt.getSubject().equals(app.getCandidateUserId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed.");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + app.getCvFileName() + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(app.getCvFile());
    }

    @PatchMapping(value = "/me/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApplicationDto updateMyApplication(
            @PathVariable UUID id,
            @RequestParam(required = false) String githubUrl,
            @RequestPart(value = "cv", required = false) MultipartFile cv,
            @AuthenticationPrincipal Jwt jwt
    ) throws IOException {
        return service.updateMyApplication(id, jwt.getSubject(), githubUrl, cv);
    }

    /**
     * Candidate withdraws their own application (soft delete).
     * Only allowed when status is APPLIED.
     * Application stays in DB with WITHDRAWN status — hidden from candidate dashboard.
     * Candidate can re-apply to the same job after withdrawing.
     * DELETE /api/applications/me/{id}
     */
    @DeleteMapping("/me/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdrawMyApplication(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        service.withdrawMyApplication(id, jwt.getSubject());
    }

    @PatchMapping("/{id}/status")
    public ApplicationDto updateStatus(
            @PathVariable UUID id,
            @RequestParam ApplicationStatus status,
            @AuthenticationPrincipal Jwt jwt) {
        String actorUserId = (jwt != null && jwt.getSubject() != null) ? jwt.getSubject() : "SYSTEM";
        return service.updateStatus(id, status, actorUserId);
    }

    @GetMapping("/internal/job/{jobId}/candidate-ids")
    public List<String> getCandidateIdsByJob(@PathVariable UUID jobId) {
        return service.getCandidateUserIdsByJob(jobId);
    }

    @DeleteMapping("/internal/job/{jobId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteApplicationsForJob(@PathVariable UUID jobId) {
        service.deleteApplicationsForJob(jobId);
    }

    @PostMapping("/internal/job/{jobId}/rematch")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void rematchForJob(@PathVariable UUID jobId) {
        cvAnalysisService.rematchAllForJob(jobId);
    }

    @PostMapping("/internal/job/{jobId}/shortlist")
    public java.util.Map<String, Object> shortlist(
            @PathVariable UUID jobId,
            @RequestParam(defaultValue = "70") int threshold) {
        return service.shortlistByScore(jobId, threshold);
    }

    @PostMapping("/internal/job/{jobId}/reject-non-hired")
    public ResponseEntity<Void> rejectNonHiredForJob(@PathVariable UUID jobId) {
        service.rejectNonHiredForJob(jobId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/paged")
    public PageResponse<ApplicationDto> listPaged(
            @RequestParam(required = false) UUID applicationId,
            @RequestParam(required = false) UUID jobId,
            @RequestParam(required = false) ApplicationStatus status,
            @RequestParam(required = false) String jobTitle,
            @RequestParam(required = false) String candidateName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return service.listApplicationsPaged(applicationId, jobId, status, jobTitle, candidateName, page, size);
    }

    @GetMapping("/{id}/analysis")
    public CvAnalysis getAnalysis(@PathVariable UUID id) {
        return cvAnalysisService.getAnalysis(id);
    }

    @GetMapping("/{id}/analysis/exists")
    public boolean hasAnalysis(@PathVariable UUID id) {
        return cvAnalysisService.hasAnalysis(id);
    }

    @GetMapping("/{id}/rank")
    public java.util.Map<String, Object> getApplicationRank(@PathVariable UUID id) {
        return service.getRankForApplication(id);
    }

    @PostMapping("/{id}/analysis/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void retryAnalysis(@PathVariable UUID id) {
        Application app = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found: " + id));
        cvAnalysisService.retriggerAnalysis(app);
    }

    // ── Moderation endpoints ──────────────────────────────────────────────────

    @PostMapping("/signal/{candidateUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void signalCandidate(
            @PathVariable String candidateUserId,
            @RequestBody SignalRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String reason = request != null && request.reason() != null
                ? request.reason() : "Flagged by recruiter";
        service.flagCandidateApplications(candidateUserId, reason, jwt.getSubject());
    }

    @DeleteMapping("/signal/{candidateUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsignalCandidate(
            @PathVariable String candidateUserId,
            @RequestBody(required = false) UnsignalRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String reason = request != null && request.reason() != null
                ? request.reason() : "Signal withdrawn by recruiter";
        service.unflagCandidateApplications(candidateUserId, reason, jwt.getSubject());
    }

    @PostMapping("/internal/block/{candidateUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void blockCandidate(@PathVariable String candidateUserId) {
        service.blockCandidateApplications(candidateUserId);
    }

    @PostMapping("/internal/unblock/{candidateUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblockCandidate(@PathVariable String candidateUserId) {
        service.unblockCandidateApplications(candidateUserId);
    }

    @PostMapping("/internal/dismiss/{candidateUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismissFlag(@PathVariable String candidateUserId) {
        service.dismissCandidateFlag(candidateUserId);
    }

    // ── Request records ───────────────────────────────────────────────────────

    public record SignalRequest(String reason) {}
    public record UnsignalRequest(String reason) {}
    @GetMapping("/{applicationId}/cv-summary")
    public ResponseEntity<CvSummaryDto> getCvSummary(@PathVariable UUID applicationId) {;
        CvAnalysis cv = cvAnalysisRepository.findByApplicationId(applicationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No CV analysis found for application " + applicationId));

        SemanticMatchDto sm = cv.getSemanticMatch();
        CvSummaryDto dto = CvSummaryDto.builder()
                .candidateName(cv.getCandidateName())
                .skills(cv.getSkills())
                .summary(cv.getSummary())
                .githubScore(cv.getGithubProfile() != null
                        ? cv.getGithubProfile().getGithubScore() : null)
                .githubFrameworks(cv.getGithubProfile() != null
                        ? cv.getGithubProfile().getAllRepoFrameworks() : List.of())
                .cvSkillsNoEvidence(cv.getGithubProfile() != null
                        ? cv.getGithubProfile().getCvSkillsNoEvidence() : List.of())
                .jobFitScore(sm != null ? sm.getJobFitScore() : null)
                .preInterviewRecommendation(sm != null ? sm.getRecommendation() : null)
                .requiredSkillsMatched(sm != null && sm.getRequiredSkillsMatched() != null
                        ? sm.getRequiredSkillsMatched() : List.of())
                .requiredSkillsMissing(sm != null && sm.getRequiredSkillsMissing() != null
                        ? sm.getRequiredSkillsMissing() : List.of())
                .semanticStrengths(sm != null && sm.getStrengths() != null
                        ? sm.getStrengths() : List.of())
                .semanticWeaknesses(sm != null && sm.getWeaknesses() != null
                        ? sm.getWeaknesses() : List.of())
                .build();

        return ResponseEntity.ok(dto);
    }
}