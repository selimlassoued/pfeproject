package com.zaina.interviewservice.controllers;

import com.zaina.interviewservice.clients.UserClient;
import com.zaina.interviewservice.dto.ConsentUpdateRequest;
import com.zaina.interviewservice.dto.InterviewResponse;
import com.zaina.interviewservice.dto.ScheduleInterviewRequest;
import com.zaina.interviewservice.services.InterviewQuestionService;
import com.zaina.interviewservice.services.InterviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;


@RestController
@RequestMapping("/api/interviews")
@RequiredArgsConstructor
@Slf4j
public class InterviewController {

    private final InterviewService         interviewService;
    private final InterviewQuestionService questionService;
    private final UserClient userClient;

    @PostMapping
    public ResponseEntity<InterviewResponse> schedule(
            @RequestBody ScheduleInterviewRequest request,
            HttpServletRequest httpRequest) {

        // ── Recruiter name from JWT (already logged-in user) ──────────────────
        String auth = httpRequest.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            try {
                String token = auth.substring(7);
                String payload = token.split("\\.")[1];
                String decoded = new String(java.util.Base64.getUrlDecoder().decode(payload));
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> claims = mapper.readValue(decoded, Map.class);

                if (claims.get("name") != null)
                    request.setRecruiterName(claims.get("name").toString());

                if (claims.get("email") != null)
                    request.setRecruiterEmail(claims.get("email").toString());

                // Also grab recruiterId from token if not already set
                if (claims.get("sub") != null && request.getRecruiterId() == null)
                    request.setRecruiterId(UUID.fromString(claims.get("sub").toString()));

            } catch (Exception e) {
                log.warn("Could not extract recruiter info from JWT: {}", e.getMessage());
            }
        }

        try {
            UserClient.UserProfile profile = userClient.getUserProfile(request.getCandidateId());
            String fullName = profile.fullName();
            log.info("Fetched candidate profile: id={} name={} email={}",
                    request.getCandidateId(), fullName, profile.email());
            if (!fullName.isBlank())
                request.setCandidateName(fullName);
            // Also fix the email if it's wrong
            if (profile.email() != null && !profile.email().isBlank())
                request.setCandidateEmail(profile.email());
        } catch (Exception e) {
            log.warn("Could not fetch candidate profile for {}: {}",
                    request.getCandidateId(), e.getMessage());}

        return ResponseEntity.ok(interviewService.scheduleInterview(request));
    }

    /** Every interview across the team — drives the shared calendar. */
    @GetMapping
    public ResponseEntity<List<InterviewResponse>> getAll() {
        return ResponseEntity.ok(interviewService.getAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<InterviewResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(interviewService.getInterview(id));
    }

    @GetMapping("/application/{applicationId}")
    public ResponseEntity<List<InterviewResponse>> getByApplication(
            @PathVariable UUID applicationId) {
        return ResponseEntity.ok(interviewService.getByApplication(applicationId));
    }

    @GetMapping("/candidate/{candidateId}")
    public ResponseEntity<List<InterviewResponse>> getByCandidate(
            @PathVariable UUID candidateId) {
        return ResponseEntity.ok(interviewService.getByCandidate(candidateId));
    }

    @GetMapping("/recruiter/{recruiterId}")
    public ResponseEntity<List<InterviewResponse>> getByRecruiter(
            @PathVariable UUID recruiterId) {
        return ResponseEntity.ok(interviewService.getByRecruiter(recruiterId));
    }

    @PatchMapping("/{id}/consent")
    public ResponseEntity<InterviewResponse> updateConsent(
            @PathVariable UUID id,
            @RequestBody ConsentUpdateRequest request) {
        return ResponseEntity.ok(interviewService.updateConsent(id, request));
    }

    @PatchMapping("/{id}/start")
    public ResponseEntity<InterviewResponse> start(@PathVariable UUID id) {
        return ResponseEntity.ok(interviewService.startInterview(id));
    }

    @PatchMapping("/{id}/complete")
    public ResponseEntity<InterviewResponse> complete(@PathVariable UUID id) {
        return ResponseEntity.ok(interviewService.completeInterview(id));
    }

    @PatchMapping("/{id}/cancel")
    public ResponseEntity<InterviewResponse> cancel(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID requesterId,
            @RequestParam(defaultValue = "false") boolean admin) {
        return ResponseEntity.ok(interviewService.cancelInterview(id, requesterId, admin));
    }

    @PatchMapping("/{id}/invite")
    public ResponseEntity<InterviewResponse> invite(
            @PathVariable UUID id, @RequestParam UUID recruiterId) {
        return ResponseEntity.ok(interviewService.inviteRecruiter(id, recruiterId));
    }

    @PatchMapping("/{id}/uninvite")
    public ResponseEntity<InterviewResponse> uninvite(
            @PathVariable UUID id, @RequestParam UUID recruiterId) {
        return ResponseEntity.ok(interviewService.uninviteRecruiter(id, recruiterId));
    }

    /** The organiser admits the waiting candidate — they can then enter the Jitsi room. */
    @PatchMapping("/{id}/admit")
    public ResponseEntity<InterviewResponse> admitCandidate(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID requesterId,
            @RequestParam(defaultValue = "false") boolean admin) {
        return ResponseEntity.ok(interviewService.admitCandidate(id, requesterId, admin));
    }

    /** A recruiter asks the organizer to be invited to this interview. */
    @PostMapping("/{id}/request-join")
    public ResponseEntity<Void> requestJoin(
            @PathVariable UUID id,
            @RequestParam UUID requesterId,
            @RequestParam(required = false) String requesterName) {
        interviewService.requestToJoin(id, requesterId, requesterName);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/recording")
    public ResponseEntity<InterviewResponse> uploadRecording(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "role", defaultValue = "recruiter") String role,
            @RequestParam(value = "joinedAt", required = false) String joinedAt,
            @RequestParam(value = "leftAt", required = false) String leftAt) {
        return ResponseEntity.ok(
                interviewService.saveRecording(id, file, role, joinedAt, leftAt));
    }

    @PostMapping("/{id}/left")
    public ResponseEntity<Void> participantLeft(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        interviewService.handleParticipantLeft(id, body.get("role"));
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}/recording")
    public ResponseEntity<Resource> downloadRecording(
            @PathVariable UUID id,
            @RequestParam(value = "role", defaultValue = "recruiter") String role) throws IOException {
        Path path = interviewService.getRecordingPath(id, role);
        Resource resource = new UrlResource(path.toUri());
        if (!resource.exists()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recording not found");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/webm"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"interview-" + id + "-" + role + ".webm\"")
                .body(resource);
    }

    @GetMapping("/{id}/token")
    public ResponseEntity<Map<String, String>> getJitsiToken(
            @PathVariable UUID id,
            @RequestParam String userId,
            @RequestParam String displayName,
            @RequestParam String email,
            @RequestParam boolean moderator) {
        String token = interviewService.getJitsiToken(id, userId, displayName, email, moderator);
        return ResponseEntity.ok(Map.of("token", token));
    }

    @PostMapping("/{id}/questions/generate")
    public ResponseEntity<Void> generateQuestions(@PathVariable UUID id) {
        questionService.generateQuestions(id);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/{id}/questions")
    public ResponseEntity<List<InterviewQuestionService.InterviewQuestionDto>> getQuestions(
            @PathVariable UUID id) {
        return ResponseEntity.ok(questionService.getQuestions(id));
    }

    @PatchMapping("/{id}/questions/{questionId}")
    public ResponseEntity<Void> markQuestion(
            @PathVariable UUID id,
            @PathVariable UUID questionId,
            @RequestBody Map<String, String> body) {
        questionService.markQuestion(questionId, body.get("status"));
        return ResponseEntity.ok().build();
    }
    // InterviewController.java
    @PostMapping("/{interviewId}/retrigger-analysis")
    public ResponseEntity<InterviewResponse> retriggerAnalysis(
            @PathVariable UUID interviewId) {
        return ResponseEntity.accepted()
                .body(interviewService.retriggerAnalysis(interviewId));
    }
}