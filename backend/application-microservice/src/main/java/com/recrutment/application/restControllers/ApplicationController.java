package com.recrutment.application.restControllers;

import com.recrutment.application.dto.ApplicationDto;
import com.recrutment.application.dto.PageResponse;
import com.recrutment.application.entities.Application;
import com.recrutment.application.enums.ApplicationStatus;
import com.recrutment.application.repos.ApplicationRepo;
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

    // ── Application endpoints ─────────────────────────────────────────────────

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApplicationDto apply(
            @RequestParam UUID jobId,
            @RequestParam(required = false) String githubUrl,
            @RequestPart("cv") MultipartFile cv,
            @AuthenticationPrincipal Jwt jwt
    ) throws IOException {
        String candidateUserId = jwt.getSubject();
        return service.apply(jobId, candidateUserId, githubUrl, cv);
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

    @GetMapping("/check-github")
    public boolean checkGithub(@RequestParam String url) {
        return service.checkGithubLink(url);
    }

    @GetMapping("/me/by-job/{jobId}")
    public ApplicationDto myApplicationByJob(
            @PathVariable UUID jobId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        String candidateUserId = jwt.getSubject();
        return service.getMyApplicationByJob(jobId, candidateUserId);
    }

    @GetMapping("/me")
    public List<ApplicationDto> myApplications(@AuthenticationPrincipal Jwt jwt) {
        String candidateUserId = jwt.getSubject();
        return service.listMyApplications(candidateUserId);
    }

    @GetMapping("/me/{id}")
    public ApplicationDto getMyOne(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt
    ) {
        String candidateUserId = jwt.getSubject();
        return service.getMyApplication(id, candidateUserId);
    }

    @GetMapping("/me/{id}/cv")
    public ResponseEntity<byte[]> downloadMyCv(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt
    ) {
        String candidateUserId = jwt.getSubject();

        Application app = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found: " + id));

        if (!candidateUserId.equals(app.getCandidateUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed.");
        }

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
        String candidateUserId = jwt.getSubject();
        return service.updateMyApplication(id, candidateUserId, githubUrl, cv);
    }

    @PatchMapping("/{id}/status")
    public ApplicationDto updateStatus(
            @PathVariable UUID id,
            @RequestParam ApplicationStatus status,
            @AuthenticationPrincipal Jwt jwt
    ) {
        String actorUserId = (jwt != null && jwt.getSubject() != null) ? jwt.getSubject() : "SYSTEM";
        return service.updateStatus(id, status, actorUserId);
    }

    @GetMapping("/internal/job/{jobId}/candidate-ids")
    public List<String> getCandidateIdsByJob(@PathVariable UUID jobId) {
        return service.getCandidateUserIdsByJob(jobId);
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
}