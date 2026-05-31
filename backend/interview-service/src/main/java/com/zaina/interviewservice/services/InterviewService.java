package com.zaina.interviewservice.services;

import com.zaina.interviewservice.clients.ApplicationClient;
import com.zaina.interviewservice.clients.ApplicationCvClient;
import com.zaina.interviewservice.clients.JobClient;
import com.zaina.interviewservice.clients.UserClient;
import com.zaina.interviewservice.dto.*;
import com.zaina.interviewservice.entities.*;
import com.zaina.interviewservice.exceptions.ConflictException;
import com.zaina.interviewservice.repos.InterviewRepo;
import com.zaina.interviewservice.repos.InterviewResultRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import com.zaina.interviewservice.config.RabbitMQConfig;
import com.zaina.interviewservice.messaging.AnalysisRequestMessage;
import com.zaina.interviewservice.messaging.AppEventMessage;
import com.zaina.interviewservice.messaging.InterviewEventPublisher;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewService {


    private final InterviewRepo        interviewRepo;
    private final InterviewResultRepo  interviewResultRepo;
    private final ApplicationClient    applicationClient;
    private final ApplicationCvClient  applicationCvClient;
    private final JitsiTokenService    jitsiTokenService;
    private final RabbitTemplate       rabbitTemplate;
    private final JobClient jobClient;
    private final GoogleCalendarService googleCalendarService;
    private final InterviewEventPublisher eventPublisher;
    private final UserClient userClient;

    @Value("${recordings.dir:recordings}")
    private String recordingsDir;

    /** Two interviews for the same recruiter or candidate must be at least this far apart. */
    private static final int MIN_GAP_MINUTES = 60;

    @Transactional
    public InterviewResponse scheduleInterview(ScheduleInterviewRequest request) {

        boolean hasActive = interviewRepo
                .findByApplicationId(request.getApplicationId())
                .stream()
                .anyMatch(i -> i.getStatus() == InterviewStatus.SCHEDULED
                        || i.getStatus() == InterviewStatus.IN_PROGRESS);

        if (hasActive) {
            throw new ConflictException(
                    "An active interview already exists for this application. " +
                            "Cancel or complete it before scheduling a new one."
            );
        }

        // ── Neither the recruiter nor the candidate can be double-booked ─────
        LocalDateTime when = request.getScheduledAt();

        if (clashes(interviewRepo.findByRecruiterId(request.getRecruiterId()), when)) {
            throw new ConflictException(
                    "You already have an interview within " + MIN_GAP_MINUTES
                            + " minutes of that time. Please pick another slot."
            );
        }
        if (clashes(interviewRepo.findByCandidateId(request.getCandidateId()), when)) {
            throw new ConflictException(
                    "This candidate already has an interview within " + MIN_GAP_MINUTES
                            + " minutes of that time. Please pick another slot."
            );
        }

        String roomName = "interview-" + request.getApplicationId();
        String roomUrl  = "https://meet.jit.si/" + roomName;

        Interview interview = Interview.builder()
                .applicationId(request.getApplicationId())
                .jobId(request.getJobId())
                .recruiterId(request.getRecruiterId())
                .candidateId(request.getCandidateId())
                .candidateEmail(request.getCandidateEmail())
                .recruiterEmail(request.getRecruiterEmail())
                .jobTitle(request.getJobTitle())
                .scheduledAt(request.getScheduledAt())
                .roomUrl(roomUrl)
                .roomName(roomName)
                .recordingConsent(Boolean.TRUE.equals(request.getRecordingConsent()))
                .status(InterviewStatus.SCHEDULED)
                .recruiterName(request.getRecruiterName())
                .candidateName(request.getCandidateName())
                .build();

        Interview saved = interviewRepo.save(interview);

        // ── Fetch CV context from application service ─────────────────────────
        try {
            ApplicationCvClient.CvSummary cv =
                    applicationCvClient.getCvSummary(request.getApplicationId());
            applyCvContext(saved, cv);
            interviewRepo.save(saved);
            log.info("CV context loaded for application {} — candidate={} skills={} github={} jobFit={}",
                    request.getApplicationId(),
                    cv.candidateName(),
                    cv.skills() != null ? cv.skills().size() : 0,
                    cv.githubScore(),
                    cv.jobFitScore());
        } catch (Exception e) {
            log.error("CV context fetch FAILED for application {} — cause: {} | class: {}",
                    request.getApplicationId(), e.getMessage(), e.getClass().getName(), e);
        }

        interviewResultRepo.save(
                InterviewResult.builder()
                        .interview(saved)
                        .processingStatus(ProcessingStatus.PENDING)
                        .build()
        );
        try {
            JobClient.JobSummary job = jobClient.getJob(request.getJobId());
            saved.setJobDescription(job.description());
            saved.setJobRequirements(
                    job.requirements().stream()
                            .map(JobClient.RequirementSummary::description)
                            .collect(Collectors.toCollection(ArrayList::new))            );
            interviewRepo.save(saved);
            log.info("Job context loaded for job {}", request.getJobId());
        } catch (Exception e) {
            log.warn("Could not fetch job context for {} — transcription prompt will be generic: {}",
                    request.getJobId(), e.getMessage());
        }

        try {
            applicationClient.updateStatus(
                    request.getApplicationId(),
                    new ApplicationStatusUpdateRequest("INTERVIEW_PHASE"));
            log.info("Application {} moved to INTERVIEW_PHASE", request.getApplicationId());
        } catch (Exception e) {
            log.error("Could not update application status for {}: {}", request.getApplicationId(), e.getMessage());
        }

        // ── Mirror onto the recruiter's Google Calendar, if they linked it ────
        try {
            String eventId = googleCalendarService.createInterviewEvent(
                    saved.getRecruiterId(), saved.getJobTitle(),
                    saved.getCandidateEmail(), saved.getScheduledAt(), saved.getRoomUrl());
            if (eventId != null) {
                saved.setGoogleEventId(eventId);
                interviewRepo.save(saved);
                log.info("Interview {} mirrored to Google Calendar (event {})",
                        saved.getId(), eventId);
            }
        } catch (Exception e) {
            log.warn("Could not push interview {} to Google Calendar: {}",
                    saved.getId(), e.getMessage());
        }

        log.info("Interview {} scheduled for application {}", saved.getId(), saved.getApplicationId());
        // Real-time ping: candidate and recruiter widgets refetch without page refresh.
        publishListChange("INTERVIEW_SCHEDULED", saved);
        return toResponse(saved);
    }

    public InterviewResponse updateConsent(UUID interviewId, ConsentUpdateRequest request) {
        Interview interview = findById(interviewId);
        if (interview.getStatus() == InterviewStatus.CANCELLED
                || interview.getStatus() == InterviewStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Consent cannot be updated for cancelled or completed interviews");
        }
        interview.setRecordingConsent(request.getRecordingConsent());
        return toResponse(interviewRepo.save(interview));
    }

    public InterviewResponse startInterview(UUID interviewId) {
        Interview interview = findById(interviewId);
        interview.setStatus(InterviewStatus.IN_PROGRESS);
        return toResponse(interviewRepo.save(interview));
    }

    @Transactional
    public InterviewResponse completeInterview(UUID interviewId) {
        Interview interview = findById(interviewId);
        interview.setStatus(InterviewStatus.COMPLETED);
        interview.setCompletedAt(LocalDateTime.now());
        return toResponse(interviewRepo.save(interview));
    }

    @Transactional
    public InterviewResponse cancelInterview(UUID interviewId, UUID requesterId, boolean admin) {
        Interview interview = findById(interviewId);
        // Only the recruiter who scheduled it may cancel — admins/superadmins override.
        if (!admin && (requesterId == null
                || !requesterId.equals(interview.getRecruiterId()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the recruiter who scheduled this interview can cancel it.");
        }
        interview.setStatus(InterviewStatus.CANCELLED);
        Interview saved = interviewRepo.save(interview);
        publishListChange("INTERVIEW_CANCELLED", saved);
        return toResponse(saved);
    }

    public InterviewResponse getInterview(UUID interviewId) {
        return toResponse(findById(interviewId));
    }

    /** Invite another recruiter so they too may join this interview's room. */
    @Transactional
    public InterviewResponse inviteRecruiter(UUID interviewId, UUID recruiterId) {
        Interview interview = findById(interviewId);
        List<UUID> invited = interview.getInvitedRecruiterIds();
        if (invited == null) invited = new ArrayList<>();
        if (!recruiterId.equals(interview.getRecruiterId()) && !invited.contains(recruiterId)) {
            invited.add(recruiterId);
            interview.setInvitedRecruiterIds(invited);
            interviewRepo.save(interview);

            Map<String, Object> payload = baseEventPayload(interview);
            payload.put("invitedRecruiterId", recruiterId.toString());
            publishEvent("INTERVIEW_INVITE", payload);
        }
        return toResponse(interview);
    }

    /** A recruiter asks the interview's organizer to be invited. */
    public void requestToJoin(UUID interviewId, UUID requesterId, String requesterName) {
        Interview interview = findById(interviewId);
        Map<String, Object> payload = baseEventPayload(interview);
        payload.put("organizerId", interview.getRecruiterId().toString());
        payload.put("requesterId", requesterId.toString());
        payload.put("requesterName",
                (requesterName != null && !requesterName.isBlank()) ? requesterName : "A recruiter");
        publishEvent("INTERVIEW_JOIN_REQUEST", payload);
    }

    private Map<String, Object> baseEventPayload(Interview interview) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("interviewId", interview.getId().toString());
        payload.put("applicationId", interview.getApplicationId().toString());
        payload.put("jobTitle",
                interview.getJobTitle() != null ? interview.getJobTitle() : "an interview");
        return payload;
    }

    private void publishEvent(String type, Map<String, Object> payload) {
        AppEventMessage evt = new AppEventMessage();
        evt.setEventType(type);
        evt.setProducer("interview-service");
        evt.setPayload(payload);
        eventPublisher.publish("notify.interview", evt);
    }

    /**
     * Publish a lightweight "interview list changed" event so each participant's
     * navbar widget reloads in real time. Carries enough context for the
     * notification-microservice to push a STOMP ping to every affected user.
     */
    private void publishListChange(String type, Interview interview) {
        try {
            Map<String, Object> payload = baseEventPayload(interview);
            payload.put("candidateId", interview.getCandidateId().toString());
            payload.put("recruiterId", interview.getRecruiterId().toString());
            if (interview.getInvitedRecruiterIds() != null
                    && !interview.getInvitedRecruiterIds().isEmpty()) {
                payload.put("invitedRecruiterIds",
                        interview.getInvitedRecruiterIds().stream()
                                .map(UUID::toString).toList());
            }
            publishEvent(type, payload);
        } catch (Exception e) {
            log.warn("Could not publish {} event for interview {}: {}",
                    type, interview.getId(), e.getMessage());
        }
    }

    /**
     * The organiser (or an admin) admits the waiting candidate — the candidate's
     * waiting page polls this flag and forwards them into the Jitsi room.
     */
    @Transactional
    public InterviewResponse admitCandidate(UUID interviewId, UUID requesterId, boolean admin) {
        Interview interview = findById(interviewId);
        if (!admin && (requesterId == null
                || !requesterId.equals(interview.getRecruiterId()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the recruiter who scheduled this interview can admit the candidate.");
        }
        interview.setCandidateAdmitted(true);
        return toResponse(interviewRepo.save(interview));
    }

    /** Revoke a previously invited recruiter. */
    @Transactional
    public InterviewResponse uninviteRecruiter(UUID interviewId, UUID recruiterId) {
        Interview interview = findById(interviewId);
        if (interview.getInvitedRecruiterIds() != null) {
            interview.getInvitedRecruiterIds().remove(recruiterId);
            interviewRepo.save(interview);
        }
        return toResponse(interview);
    }

    public List<InterviewResponse> getByApplication(UUID applicationId) {
        return interviewRepo.findByApplicationId(applicationId)
                .stream().map(this::toResponse).collect(Collectors.toCollection(ArrayList::new));
    }

    public List<InterviewResponse> getByCandidate(UUID candidateId) {
        return interviewRepo.findByCandidateId(candidateId)
                .stream().map(this::toResponse).collect(Collectors.toCollection(ArrayList::new));
    }

    public List<InterviewResponse> getByRecruiter(UUID recruiterId) {
        return interviewRepo.findByRecruiterId(recruiterId)
                .stream().map(this::toResponse).collect(Collectors.toCollection(ArrayList::new));
    }

    /** Every interview across the team — drives the shared calendar. */
    public List<InterviewResponse> getAll() {
        return interviewRepo.findAll()
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    private Interview findById(UUID id) {
        return interviewRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Interview not found: " + id));
    }

    /** True if any active interview in the list is within MIN_GAP_MINUTES of {@code when}. */
    private boolean clashes(List<Interview> interviews, LocalDateTime when) {
        return interviews.stream()
                .filter(i -> i.getStatus() == InterviewStatus.SCHEDULED
                        || i.getStatus() == InterviewStatus.IN_PROGRESS)
                .anyMatch(i -> Math.abs(
                        Duration.between(i.getScheduledAt(), when).toMinutes()) < MIN_GAP_MINUTES);
    }

    private InterviewResponse toResponse(Interview i) {
        return InterviewResponse.builder()
                .id(i.getId())
                .applicationId(i.getApplicationId())
                .jobId(i.getJobId())
                .jobTitle(i.getJobTitle())
                .candidateEmail(i.getCandidateEmail())
                .recruiterEmail(i.getRecruiterEmail())
                .recruiterId(i.getRecruiterId())
                .candidateName(i.getCandidateName())
                .recruiterName(i.getRecruiterName())
                .scheduledAt(i.getScheduledAt())
                .roomUrl(i.getRoomUrl())
                .recordingConsent(i.getRecordingConsent())
                .status(i.getStatus())
                .createdAt(i.getCreatedAt())
                .invitedRecruiterIds(i.getInvitedRecruiterIds())
                .candidateAdmitted(i.getCandidateAdmitted())
                .build();
    }

    public InterviewResponse saveRecording(UUID interviewId, MultipartFile file,
                                           String role, String joinedAt, String leftAt) {
        Interview interview = findById(interviewId);

        if (interview.getStatus() == InterviewStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot upload recording for a cancelled interview");
        }
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Audio file is empty");
        }

        Path dir = Path.of(recordingsDir).resolve(interviewId.toString());

        try {
            Files.createDirectories(dir);
            Path dest = dir.resolve(role + ".webm");
            file.transferTo(dest);

            if (joinedAt != null && leftAt != null) {
                Path meta = dir.resolve(role + "-meta.json");
                String json = String.format(
                        "{\"role\":\"%s\",\"joinedAt\":\"%s\",\"leftAt\":\"%s\"}",
                        role, joinedAt, leftAt);
                Files.writeString(meta, json);
            }
            log.info("Recording saved for interview {} role {}", interviewId, role);
        } catch (IOException e) {
            log.error("Failed to save recording for interview {}: {}", interviewId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to save recording");
        }

        InterviewResult result = interviewResultRepo.findByInterviewId(interviewId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "InterviewResult not found"));

        boolean bothExist = Files.exists(dir.resolve("recruiter.webm"))
                && Files.exists(dir.resolve("candidate.webm"));

        if (bothExist) {
            result.setProcessingStatus(ProcessingStatus.TRANSCRIBING);
            interviewResultRepo.save(result);
            Interview freshInterview = findById(interviewId);
            // ── If context is still missing, attempt a live re-fetch ──────────
            boolean contextMissing = freshInterview.getCandidateName() == null
                    || freshInterview.getCandidateName().isBlank();

            if (contextMissing) {
                log.warn("CV context missing on interview {} — attempting re-fetch", interviewId);
                try {
                    ApplicationCvClient.CvSummary cv =
                            applicationCvClient.getCvSummary(freshInterview.getApplicationId());
                    applyCvContext(freshInterview, cv);
                    interviewRepo.save(freshInterview);
                    log.info("CV context recovered for interview {}", interviewId);
                } catch (Exception e) {
                    log.warn("CV context re-fetch failed — analysis will run without it: {}",
                            e.getMessage());
                }
            }

            boolean jobMissing = freshInterview.getJobDescription() == null
                    || freshInterview.getJobDescription().isBlank();

            if (jobMissing) {
                log.warn("Job context missing on interview {} — attempting re-fetch", interviewId);
                try {
                    JobClient.JobSummary job = jobClient.getJob(freshInterview.getJobId());
                    freshInterview.setJobDescription(job.description());
                    freshInterview.setJobRequirements(
                            job.requirements().stream()
                                    .map(JobClient.RequirementSummary::description)
                                    .collect(Collectors.toCollection(ArrayList::new))
                    );
                    interviewRepo.save(freshInterview);
                    log.info("Job context recovered for interview {}", interviewId);
                } catch (Exception e) {
                    log.warn("Job context re-fetch failed: {}", e.getMessage());
                }
            }
            log.info("Both recordings present for {} — publishing analysis job", interviewId);
            String recruiterJoinedAt = readMetaField(dir, "recruiter", "joinedAt");
            String candidateJoinedAt = readMetaField(dir, "candidate", "joinedAt");
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.ANALYSIS_EXCHANGE,
                    RabbitMQConfig.ANALYSIS_ROUTING,
                    buildAnalysisRequest(freshInterview, interviewId,
                            recruiterJoinedAt, candidateJoinedAt)
            );
            log.info("Analysis job published for interview {} with candidate={} job_desc_len={}",
                    interviewId,
                    freshInterview.getCandidateName(),
                    freshInterview.getJobDescription() != null
                            ? freshInterview.getJobDescription().length() : 0);
        }

        return toResponse(interviewRepo.save(interview));
    }

    @Transactional
    public void handleParticipantLeft(UUID interviewId, String role) {
        if ("recruiter".equals(role)) {
            interviewRepo.markRecruiterLeft(interviewId);
        } else if ("candidate".equals(role)) {
            interviewRepo.markCandidateLeft(interviewId);
        }

        Interview interview = findById(interviewId);
        log.info("Interview {} — {} left (recruiterLeft={}, candidateLeft={})",
                interviewId, role, interview.getRecruiterLeft(), interview.getCandidateLeft());

        if (Boolean.TRUE.equals(interview.getRecruiterLeft())
                && Boolean.TRUE.equals(interview.getCandidateLeft())) {
            interview.setStatus(InterviewStatus.COMPLETED);
            interview.setCompletedAt(LocalDateTime.now());
            interviewRepo.save(interview);
            log.info("Interview {} marked COMPLETED — both participants left", interviewId);
        }
    }

    public Path getRecordingPath(UUID interviewId, String role) {
        return Path.of(recordingsDir).resolve(interviewId.toString()).resolve(role + ".webm");
    }

    public String getJitsiToken(UUID interviewId, String userId,
                                String displayName, String email, boolean moderator) {
        Interview interview = findById(interviewId);
        return jitsiTokenService.generateToken(
                interview.getRoomName(), userId, displayName, email, moderator);
    }
    /**
     * Single source of truth for the AnalysisRequest payload — keeps the
     * initial-recording and retrigger paths from drifting apart.
     */
    private AnalysisRequestMessage buildAnalysisRequest(
            Interview interview,
            UUID interviewId,
            String recruiterJoinedAt,
            String candidateJoinedAt) {
        return new AnalysisRequestMessage(
                interviewId,
                interview.getJobTitle(),
                interview.getJobDescription(),
                interview.getJobRequirements(),
                interview.getCandidateName(),
                interview.getRecruiterName(),
                interview.getCandidateSkills(),
                interview.getCandidateSummary(),
                interview.getGithubScore(),
                interview.getGithubFrameworks(),
                interview.getCvWeaknesses(),
                recruiterJoinedAt,
                candidateJoinedAt,
                interview.getJobFitScore(),
                interview.getPreInterviewRecommendation(),
                interview.getRequiredSkillsMatched(),
                interview.getRequiredSkillsMissing(),
                interview.getSemanticStrengths(),
                interview.getSemanticWeaknesses()
        );
    }

    /**
     * Copy CV + semantic match fields from the application-service summary
     * onto the Interview entity. Centralised so scheduling, late re-fetch,
     * and retrigger all stay in sync as the summary shape evolves.
     */
    private void applyCvContext(Interview target, ApplicationCvClient.CvSummary cv) {
        target.setCandidateSkills(cv.skills() != null
                ? new ArrayList<>(cv.skills()) : new ArrayList<>());
        target.setCandidateSummary(cv.summary());
        target.setGithubScore(cv.githubScore());
        target.setGithubFrameworks(cv.githubFrameworks() != null
                ? new ArrayList<>(cv.githubFrameworks()) : new ArrayList<>());
        target.setCvWeaknesses(cv.cvSkillsNoEvidence() != null
                ? new ArrayList<>(cv.cvSkillsNoEvidence()) : new ArrayList<>());
        // Semantic match handoff — nullable, may be absent if matching hasn't
        // run yet. Empty lists are safe defaults so JPA doesn't NPE on save.
        target.setJobFitScore(cv.jobFitScore());
        target.setPreInterviewRecommendation(cv.preInterviewRecommendation());
        target.setRequiredSkillsMatched(cv.requiredSkillsMatched() != null
                ? new ArrayList<>(cv.requiredSkillsMatched()) : new ArrayList<>());
        target.setRequiredSkillsMissing(cv.requiredSkillsMissing() != null
                ? new ArrayList<>(cv.requiredSkillsMissing()) : new ArrayList<>());
        target.setSemanticStrengths(cv.semanticStrengths() != null
                ? new ArrayList<>(cv.semanticStrengths()) : new ArrayList<>());
        target.setSemanticWeaknesses(cv.semanticWeaknesses() != null
                ? new ArrayList<>(cv.semanticWeaknesses()) : new ArrayList<>());
    }

    private String readMetaField(Path dir, String role, String field) {
        try {
            Path meta = dir.resolve(role + "-meta.json");
            if (!Files.exists(meta)) return null;
            String json = Files.readString(meta);
            // Simple parse — no Jackson dependency needed for this flat structure
            String search = "\"" + field + "\":\"";
            int start = json.indexOf(search);
            if (start == -1) return null;
            start += search.length();
            int end = json.indexOf("\"", start);
            return end == -1 ? null : json.substring(start, end);
        } catch (Exception e) {
            log.warn("Could not read {}-meta.json field {}: {}", role, field, e.getMessage());
            return null;
        }
    }
    @Transactional
    public InterviewResponse retriggerAnalysis(UUID interviewId) {
        Interview interview = findById(interviewId);

        if (interview.getStatus() != InterviewStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Can only retrigger analysis for COMPLETED interviews");
        }

        Path dir           = Path.of(recordingsDir).resolve(interviewId.toString());
        Path recruiterFile = dir.resolve("recruiter.webm");
        Path candidateFile = dir.resolve("candidate.webm");

        if (!Files.exists(recruiterFile) || !Files.exists(candidateFile)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Recording files missing — cannot retrigger analysis");
        }

        // ── Re-fetch CV context (was empty at scheduling time, or schema
        //    was upgraded since the interview was scheduled — always refresh
        //    semantic match handoff here so re-runs pick up newer data). ───
        try {
            ApplicationCvClient.CvSummary cv =
                    applicationCvClient.getCvSummary(interview.getApplicationId());
            applyCvContext(interview, cv);
            log.info("[Retrigger] CV context refreshed for application {} (jobFit={})",
                    interview.getApplicationId(), cv.jobFitScore());
        } catch (Exception e) {
            log.warn("[Retrigger] CV context fetch failed — proceeding without it: {}",
                    e.getMessage());
        }

        // ── Re-fetch job context ──────────────────────────────────────────────
        if (interview.getJobDescription() == null || interview.getJobDescription().isBlank()) {
            try {
                JobClient.JobSummary job = jobClient.getJob(interview.getJobId());
                interview.setJobDescription(job.description());
                interview.setJobRequirements(
                        job.requirements().stream()
                                .map(JobClient.RequirementSummary::description)
                                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new))
                );
                log.info("[Retrigger] Job context refreshed for job {}", interview.getJobId());
            } catch (Exception e) {
                log.warn("[Retrigger] Job context fetch failed — proceeding without it: {}",
                        e.getMessage());
            }
        }
        try {
            UserClient.UserProfile candidate = userClient.getUserProfile(interview.getCandidateId());
            interview.setCandidateName(candidate.fullName());
            interview.setCandidateEmail(candidate.email());
            log.info("[Retrigger] Candidate name refreshed: {}", candidate.fullName());
        } catch (Exception e) {
            log.warn("[Retrigger] Could not refresh candidate name: {}", e.getMessage());
        }

        try {
            UserClient.UserProfile recruiter = userClient.getUserProfile(interview.getRecruiterId());
            interview.setRecruiterName(recruiter.fullName());
            interview.setRecruiterEmail(recruiter.email());
            log.info("[Retrigger] Recruiter name refreshed: {}", recruiter.fullName());
        } catch (Exception e) {
            log.warn("[Retrigger] Could not refresh recruiter name: {}", e.getMessage());
        }

        interviewRepo.save(interview);

        // ── Reset the result row ──────────────────────────────────────────────
        InterviewResult result = interviewResultRepo
                .findByInterviewId(interviewId)
                .orElseGet(() -> InterviewResult.builder()
                        .interview(interview)
                        .build());
        result.setProcessingStatus(ProcessingStatus.TRANSCRIBING);
        result.setTranscript(null);
        result.setSummary(null);
        result.setCandidateScore(null);
        result.setCandidateStrengths(null);
        result.setCandidateWeaknesses(null);
        result.setSuggestedQuestions(null);
        result.setHiringRecommendation(null);
        result.setProcessingError(null);
        result.setProcessedAt(null);
        interviewResultRepo.save(result);

        // ── Read join timestamps from saved metadata ──────────────────────────
        String recruiterJoinedAt = readMetaField(dir, "recruiter", "joinedAt");
        String candidateJoinedAt = readMetaField(dir, "candidate",  "joinedAt");

        // ── Publish analysis job ──────────────────────────────────────────────
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.ANALYSIS_EXCHANGE,
                RabbitMQConfig.ANALYSIS_ROUTING,
                buildAnalysisRequest(interview, interviewId,
                        recruiterJoinedAt, candidateJoinedAt)
        );

        log.info("[Retrigger] Analysis job published for interview {}", interviewId);
        return toResponse(interview);
    }
}