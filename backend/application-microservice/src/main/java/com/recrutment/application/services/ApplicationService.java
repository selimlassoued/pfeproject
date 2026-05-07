package com.recrutment.application.services;

import com.recrutment.application.clients.AuditClient;
import com.recrutment.application.clients.JobClient;
import com.recrutment.application.clients.JobClient.JobDto;
import com.recrutment.application.clients.UserClient;
import com.recrutment.application.dto.ApplicationDto;
import com.recrutment.application.dto.PageResponse;
import com.recrutment.application.dto.SemanticMatchDto;
import com.recrutment.application.entities.Application;
import com.recrutment.application.entities.CvAnalysis;
import com.recrutment.application.enums.ApplicationStatus;
import com.recrutment.application.messaging.AppEventMessage;
import com.recrutment.application.messaging.AppEventPublisher;
import com.recrutment.application.repos.ApplicationRepo;
import com.recrutment.application.repos.CvAnalysisRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataIntegrityViolationException;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Transactional
@lombok.extern.slf4j.Slf4j
public class ApplicationService {
    private static final Pattern GITHUB_PROFILE_URL =
            Pattern.compile("^https?://(www\\.)?github\\.com/([A-Za-z0-9-]+)/*$");

    // ── Allowed status transitions (normal pipeline only) ─────────────────────
    private static final Map<ApplicationStatus, List<ApplicationStatus>> ALLOWED_TRANSITIONS = Map.of(
            ApplicationStatus.APPLIED,          List.of(ApplicationStatus.UNDER_REVIEW, ApplicationStatus.REJECTED),
            ApplicationStatus.UNDER_REVIEW,     List.of(ApplicationStatus.INTERVIEW_PHASE, ApplicationStatus.REJECTED),
            ApplicationStatus.INTERVIEW_PHASE,  List.of(ApplicationStatus.OFFER, ApplicationStatus.REJECTED),
            ApplicationStatus.OFFER,            List.of(ApplicationStatus.HIRED, ApplicationStatus.REJECTED),
            ApplicationStatus.HIRED,            List.of(),
            ApplicationStatus.REJECTED,         List.of(),
            ApplicationStatus.FLAGGED,          List.of(),
            ApplicationStatus.BLOCKED,          List.of(),
            ApplicationStatus.WITHDRAWN,        List.of()
    );

    // Statuses from which a candidate cannot withdraw
    private static final Set<ApplicationStatus> NON_WITHDRAWABLE = Set.of(
            ApplicationStatus.HIRED,
            ApplicationStatus.REJECTED,
            ApplicationStatus.FLAGGED,
            ApplicationStatus.BLOCKED,
            ApplicationStatus.WITHDRAWN
    );

    private final CvAnalysisService cvAnalysisService;
    private final AppEventPublisher eventPublisher;
    private final ApplicationRepo repo;
    private final CvAnalysisRepo cvAnalysisRepo;
    private final JobClient jobClient;
    private final UserClient userClient;
    private final AuditClient auditClient;
    private final org.springframework.context.ApplicationEventPublisher springEventPublisher;

    // ── Transition validator ──────────────────────────────────────────────────

    private void validateTransition(ApplicationStatus from, ApplicationStatus to) {
        List<ApplicationStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(from, List.of());
        if (!allowed.contains(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot transition from " + from + " to " + to + ". Allowed: " + allowed);
        }
    }

    private ApplicationDto toDto(Application a) {
        return toDto(a, false);
    }

    private List<ApplicationDto> toDtoListRanked(List<Application> apps, boolean rankByScore) {
        // Single batch query — load all stored analyses for this page of applications
        List<UUID> ids = apps.stream().map(Application::getApplicationId).toList();
        java.util.Map<UUID, com.recrutment.application.entities.CvAnalysis> analysisMap =
                cvAnalysisRepo.findByApplicationIdIn(ids).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                com.recrutment.application.entities.CvAnalysis::getApplicationId,
                                c -> c
                        ));

        var stream = apps.stream()
                .map(this::toDto)
                .peek(dto -> {
                    if (dto.getApplicationId() == null) return;
                    var analysis = analysisMap.get(dto.getApplicationId());
                    if (analysis == null || analysis.getSemanticMatch() == null) return;
                    var match = analysis.getSemanticMatch();
                    if (dto.getJobFitScore() == null)
                        dto.setJobFitScore(match.getJobFitScore());
                    if (dto.getScoreExplanation() == null)
                        dto.setScoreExplanation(match.getScoreExplanation());
                });

        // Rank by score only within a specific job's pool.
        // When showing all jobs together, score comparison is meaningless
        // (85% for an easy job ≠ 85% for a hard job).
        if (rankByScore) {
            stream = stream.sorted(java.util.Comparator.comparingInt(
                    (ApplicationDto d) -> d.getJobFitScore() != null ? d.getJobFitScore() : 0
            ).reversed());
        }

        return stream.toList();
    }

    private ApplicationDto toDto(Application a, boolean includeSemanticMatch) {
        JobDto job = null;
        String jobTitle = null;
        String candidateName = null;
        SemanticMatchDto semanticMatch = null;

        try {
            job = jobClient.getJob(a.getJobId());
            jobTitle = (job != null ? job.getTitle() : null);
        } catch (Exception ignored) {}

        try {
            var user = userClient.getUser(a.getCandidateUserId());
            if (user != null) {
                String fn = user.getFirstName() == null ? "" : user.getFirstName().trim();
                String ln = user.getLastName() == null ? "" : user.getLastName().trim();
                String full = (fn + " " + ln).trim();
                candidateName = full.isBlank() ? user.getUsername() : full;
            }
        } catch (Exception ignored) {}

        if (includeSemanticMatch) {
            try {
                CvAnalysis analysis = cvAnalysisRepo.findByApplicationId(a.getApplicationId()).orElse(null);
                semanticMatch = (analysis != null ? analysis.getSemanticMatch() : null);
            } catch (Exception ignored) {}
        }

        return new ApplicationDto(
                a.getApplicationId(), a.getJobId(), a.getCandidateUserId(),
                a.getGithubUrl(), a.getStatus(), a.getAppliedAt(),
                a.getCvFileName(), a.getCvContentType(), jobTitle, candidateName,
                semanticMatch != null ? semanticMatch.getJobFitScore() : null,
                semanticMatch != null ? semanticMatch.getRequiredSkillsMatched() : List.of(),
                semanticMatch != null ? semanticMatch.getRequiredSkillsMissing() : List.of(),
                semanticMatch != null ? semanticMatch.getExperienceGap() : null,
                semanticMatch != null ? semanticMatch.getSeniorityMatch() : null,
                semanticMatch != null ? semanticMatch.getEmbeddingScore() : null,
                semanticMatch != null ? semanticMatch.getSkillScores() : List.of(),
                semanticMatch != null ? semanticMatch.getRequirementScores() : List.of(),
                semanticMatch != null ? semanticMatch.getStrengths() : List.of(),
                semanticMatch != null ? semanticMatch.getWeaknesses() : List.of(),
                semanticMatch != null ? semanticMatch.getRecommendation() : null,
                semanticMatch != null ? semanticMatch.getInterviewQuestions() : List.of(),
                semanticMatch != null ? semanticMatch.getScoreExplanation() : null
        );
    }

    // ── Apply ─────────────────────────────────────────────────────────────────

    public ApplicationDto apply(UUID jobId, String candidateUserId, String githubUrl, MultipartFile cv) throws IOException {
        // ATS rule: only allow applying to PUBLISHED jobs with quota remaining
        JobDto job = jobClient.getJob(jobId);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Job service unavailable.");
        }
        String js = job.getJobStatus() != null ? job.getJobStatus().toUpperCase() : "PUBLISHED";
        if (!"PUBLISHED".equals(js)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This job is not accepting applications.");
        }
        if (job.getOpenings() != null && job.getHiredCount() != null && job.getHiredCount() >= job.getOpenings()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This job has reached its quota.");
        }

        if (cv == null || cv.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CV file is required.");

        String ct = cv.getContentType();
        boolean isPdf = "application/pdf".equalsIgnoreCase(ct)
                || (cv.getOriginalFilename() != null && cv.getOriginalFilename().toLowerCase().endsWith(".pdf"));
        if (!isPdf)
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only PDF is allowed.");

        // Block if candidate already has an active application for this job
        // Ignores WITHDRAWN — candidate can re-apply after withdrawing
        repo.findByJobIdAndCandidateUserIdAndStatusNot(jobId, candidateUserId, ApplicationStatus.WITHDRAWN)
                .ifPresent(a -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "You already applied to this job.");
                });

        // Block flagged/blocked candidates from applying
        boolean isModerated = repo.findByCandidateUserId(candidateUserId)
                .stream()
                .anyMatch(a -> a.getStatus() == ApplicationStatus.FLAGGED
                        || a.getStatus() == ApplicationStatus.BLOCKED);
        if (isModerated) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your account is under review. You cannot submit new applications.");
        }

        Application app = Application.builder()
                .jobId(jobId).candidateUserId(candidateUserId).githubUrl(githubUrl)
                .status(ApplicationStatus.APPLIED).appliedAt(Instant.now())
                .cvFile(cv.getBytes())
                .cvFileName(cv.getOriginalFilename() == null ? "cv.pdf" : cv.getOriginalFilename())
                .cvContentType("application/pdf")
                .build();

        try {
            Application saved = repo.save(app);
            // AI pipeline runs immediately after apply
            cvAnalysisService.analyzeAsync(saved);
            return toDto(saved);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You already applied to this job.");
        }
    }

    // ── GitHub check ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public boolean checkGithubLink(String url) {
        if (url == null || url.isBlank()) return false;
        var matcher = GITHUB_PROFILE_URL.matcher(url.trim());
        if (!matcher.matches()) return false;
        String normalized = "https://github.com/" + matcher.group(2);
        try {
            URL target = URI.create(normalized).toURL();
            HttpURLConnection conn = (HttpURLConnection) target.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            return conn.getResponseCode() >= 200 && conn.getResponseCode() < 400;
        } catch (Exception ignored) { return false; }
    }

    // ── Withdraw ──────────────────────────────────────────────────────────────

    /**
     * Candidate withdraws their application.
     * Allowed from any status except: HIRED, REJECTED, FLAGGED, BLOCKED, WITHDRAWN.
     * The candidate may have found a better offer at any stage — it's their right.
     * Application stays in DB with WITHDRAWN status — data preserved for the company.
     * Candidate can re-apply to the same job after withdrawing.
     */
    @Transactional
    public void withdrawMyApplication(UUID id, String candidateUserId) {
        Application app = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Application not found: " + id));

        if (!candidateUserId.equals(app.getCandidateUserId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed.");

        if (NON_WITHDRAWABLE.contains(app.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "You cannot withdraw this application. Current status: " + app.getStatus());
        }

        // Save status before changing — needed for audit trail
        ApplicationStatus statusBeforeWithdraw = app.getStatus();

        app.setStatus(ApplicationStatus.WITHDRAWN);
        repo.save(app);

        // Audit trail — includes previous status so recruiter knows from which stage candidate withdrew
        AppEventMessage evt = new AppEventMessage();
        evt.setEventType("APPLICATION_WITHDRAWN");
        evt.setProducer("application-microservice");

        AppEventMessage.Actor actor = new AppEventMessage.Actor();
        actor.setUserId(candidateUserId);
        evt.setActor(actor);

        AppEventMessage.Target target = new AppEventMessage.Target();
        target.setType("APPLICATION");
        target.setId(id.toString());
        evt.setTarget(target);

        // Changes: shows old → new status transition
        evt.setChanges(java.util.Map.of(
                "status", java.util.Map.of(
                        "old", statusBeforeWithdraw.name(),
                        "new", ApplicationStatus.WITHDRAWN.name()
                )
        ));

        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("applicationId", id.toString());
        payload.put("jobId", app.getJobId().toString());
        payload.put("previousStatus", statusBeforeWithdraw.name());
        evt.setPayload(payload);

        eventPublisher.publish("audit.application", evt);
    }

    // ── Moderation: Flag ──────────────────────────────────────────────────────

    @Transactional
    public void flagCandidateApplications(String candidateUserId, String reason, String flaggedBy) {
        List<Application> apps = repo.findByCandidateUserId(candidateUserId);

        for (Application app : apps) {
            if (app.getStatus() == ApplicationStatus.FLAGGED
                    || app.getStatus() == ApplicationStatus.BLOCKED
                    || app.getStatus() == ApplicationStatus.WITHDRAWN) continue;
            app.setPreviousStatus(app.getStatus());
            app.setStatus(ApplicationStatus.FLAGGED);
        }
        repo.saveAll(apps);

        AppEventMessage evt = new AppEventMessage();
        evt.setEventType("CANDIDATE_FLAGGED");
        evt.setProducer("application-microservice");
        evt.setReason(reason);

        AppEventMessage.Actor actor = new AppEventMessage.Actor();
        actor.setUserId(flaggedBy);
        evt.setActor(actor);

        AppEventMessage.Target target = new AppEventMessage.Target();
        target.setType("CANDIDATE");
        target.setId(candidateUserId);
        evt.setTarget(target);

        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("candidateUserId", candidateUserId);
        payload.put("applicationsAffected", apps.size());
        evt.setPayload(payload);

        eventPublisher.publish("audit.application", evt);
        eventPublisher.publish("notify.admin", evt);
    }

    // ── Moderation: Unflag ────────────────────────────────────────────────────

    @Transactional
    public void unflagCandidateApplications(String candidateUserId, String reason, String unflaggedBy) {
        String lastFlaggedBy = auditClient.getLastFlaggedBy(candidateUserId);
        if (lastFlaggedBy != null && !lastFlaggedBy.equals(unflaggedBy)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the recruiter who flagged this candidate can remove the signal.");
        }

        List<Application> apps = repo.findByCandidateUserIdAndStatus(
                candidateUserId, ApplicationStatus.FLAGGED);

        for (Application app : apps) {
            ApplicationStatus restore = app.getPreviousStatus() != null
                    ? app.getPreviousStatus() : ApplicationStatus.APPLIED;
            app.setStatus(restore);
            app.setPreviousStatus(null);
        }
        repo.saveAll(apps);

        AppEventMessage evt = new AppEventMessage();
        evt.setEventType("CANDIDATE_UNFLAGGED");
        evt.setProducer("application-microservice");
        evt.setReason(reason);

        AppEventMessage.Actor actor = new AppEventMessage.Actor();
        actor.setUserId(unflaggedBy);
        evt.setActor(actor);

        AppEventMessage.Target target = new AppEventMessage.Target();
        target.setType("CANDIDATE");
        target.setId(candidateUserId);
        evt.setTarget(target);

        eventPublisher.publish("audit.application", evt);
    }

    // ── Moderation: Block ─────────────────────────────────────────────────────

    @Transactional
    public void blockCandidateApplications(String candidateUserId) {
        List<Application> apps = repo.findByCandidateUserId(candidateUserId);
        for (Application app : apps) {
            if (app.getStatus() == ApplicationStatus.BLOCKED
                    || app.getStatus() == ApplicationStatus.WITHDRAWN) continue;
            if (app.getPreviousStatus() == null) app.setPreviousStatus(app.getStatus());
            app.setStatus(ApplicationStatus.BLOCKED);
        }
        repo.saveAll(apps);
    }

    // ── Moderation: Unblock ───────────────────────────────────────────────────

    @Transactional
    public void unblockCandidateApplications(String candidateUserId) {
        List<Application> apps = repo.findByCandidateUserId(candidateUserId);
        for (Application app : apps) {
            if (app.getStatus() != ApplicationStatus.BLOCKED
                    && app.getStatus() != ApplicationStatus.FLAGGED) continue;
            ApplicationStatus restore = app.getPreviousStatus() != null
                    ? app.getPreviousStatus() : ApplicationStatus.APPLIED;
            app.setStatus(restore);
            app.setPreviousStatus(null);
        }
        repo.saveAll(apps);
    }

    // ── Moderation: Dismiss ───────────────────────────────────────────────────

    @Transactional
    public void dismissCandidateFlag(String candidateUserId) {
        List<Application> apps = repo.findByCandidateUserIdAndStatus(
                candidateUserId, ApplicationStatus.FLAGGED);
        for (Application app : apps) {
            ApplicationStatus restore = app.getPreviousStatus() != null
                    ? app.getPreviousStatus() : ApplicationStatus.APPLIED;
            app.setStatus(restore);
            app.setPreviousStatus(null);
        }
        repo.saveAll(apps);

        AppEventMessage evt = new AppEventMessage();
        evt.setEventType("CANDIDATE_SIGNAL_DISMISSED");
        evt.setProducer("application-microservice");

        AppEventMessage.Target target = new AppEventMessage.Target();
        target.setType("CANDIDATE");
        target.setId(candidateUserId);
        evt.setTarget(target);

        eventPublisher.publish("audit.application", evt);
    }

    // ── Normal CRUD ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ApplicationDto> listApplications(UUID applicationId, ApplicationStatus status,
                                                 String jobTitle, String candidateName) {
        List<Application> base;
        if (applicationId != null)  base = repo.findById(applicationId).map(List::of).orElseGet(List::of);
        else if (status != null)    base = repo.findByStatus(status);
        else                        base = repo.findAll();

        String jt = jobTitle == null ? null : jobTitle.trim().toLowerCase();
        String cn = candidateName == null ? null : candidateName.trim().toLowerCase();
        return toDtoListRanked(base, false).stream()
                .filter(d -> jt == null || jt.isBlank() || (d.getJobTitle() != null && d.getJobTitle().toLowerCase().contains(jt)))
                .filter(d -> cn == null || cn.isBlank() || (d.getCandidateName() != null && d.getCandidateName().toLowerCase().contains(cn)))
                .toList();
    }

    @Transactional(readOnly = true)
    public ApplicationDto getOne(UUID id) {
        return toDto(repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Application not found: " + id)), false);
    }

    @Transactional(readOnly = true)
    public ApplicationDto getMyApplicationByJob(UUID jobId, String candidateUserId) {
        return toDto(repo.findByJobIdAndCandidateUserIdAndStatusNot(
                        jobId, candidateUserId, ApplicationStatus.WITHDRAWN)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No application for this job.")), false);
    }

    @Transactional(readOnly = true)
    public List<ApplicationDto> listMyApplications(String candidateUserId) {
        return repo.findByCandidateUserIdAndStatusNot(candidateUserId, ApplicationStatus.WITHDRAWN)
                .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<String> getCandidateUserIdsByJob(UUID jobId) {
        return repo.findByJobId(jobId).stream()
                .map(Application::getCandidateUserId).distinct().toList();
    }

    @Transactional(readOnly = true)
    public ApplicationDto getMyApplication(UUID id, String candidateUserId) {
        Application app = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Application not found: " + id));
        if (!candidateUserId.equals(app.getCandidateUserId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed.");
        return toDto(app, false);
    }

    @Transactional(readOnly = true)
    public SemanticMatchDto getSemanticMatch(UUID id) {
        Application app = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Application not found: " + id));
        if (!cvAnalysisService.hasAnalysis(app.getApplicationId())) return null;
        return cvAnalysisService.getStoredSemanticMatch(app.getApplicationId());
    }

    @Transactional
    public ApplicationDto updateMyApplication(UUID id, String candidateUserId,
                                              String githubUrl, MultipartFile cv) throws IOException {
        Application app = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Application not found: " + id));
        if (!candidateUserId.equals(app.getCandidateUserId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed.");
        if (app.getStatus() != ApplicationStatus.APPLIED)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "You can update the application only while status is APPLIED.");

        boolean changed = false;
        if (githubUrl != null) {
            String g = githubUrl.trim();
            String normalized = g.isEmpty() ? null : g;
            if (!java.util.Objects.equals(app.getGithubUrl(), normalized)) changed = true;
            app.setGithubUrl(normalized);
        }

        if (cv != null && !cv.isEmpty()) {
            String ct = cv.getContentType();
            boolean isPdf = "application/pdf".equalsIgnoreCase(ct)
                    || (cv.getOriginalFilename() != null && cv.getOriginalFilename().toLowerCase().endsWith(".pdf"));
            if (!isPdf) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only PDF is allowed.");
            app.setCvFile(cv.getBytes());
            app.setCvFileName(cv.getOriginalFilename() == null ? "cv.pdf" : cv.getOriginalFilename());
            app.setCvContentType("application/pdf");
            changed = true;
        }
        Application saved = repo.save(app);
        // Re-run AI pipeline whenever candidate updates in APPLIED
        if (changed) cvAnalysisService.analyzeAsync(saved);
        return toDto(saved);
    }

    @Transactional
    public ApplicationDto updateStatus(UUID id, ApplicationStatus status, String actorUserId) {
        if (status == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status is required.");
        String actorId = (actorUserId != null && !actorUserId.isBlank()) ? actorUserId : "SYSTEM";

        Application app = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Application not found: " + id));

        ApplicationStatus oldStatus = app.getStatus();
        validateTransition(oldStatus, status);

        // ── Quota guard for OFFER: hired + current offers must not exceed openings ─
        if (status == ApplicationStatus.OFFER || status == ApplicationStatus.HIRED) {
            try {
                JobDto quotaJob = jobClient.getJob(app.getJobId());
                if (quotaJob != null && quotaJob.getOpenings() != null) {
                    int openings = quotaJob.getOpenings();
                    // Treat null hiredCount as 0 — never skip the check
                    int hired = quotaJob.getHiredCount() != null ? quotaJob.getHiredCount() : 0;
                    long currentOffers = repo.findByJobIdAndStatus(app.getJobId(), ApplicationStatus.OFFER).stream()
                            .filter(a -> !a.getApplicationId().equals(app.getApplicationId()))
                            .count();

                    // Count actual HIRED applications in DB as ground truth
                    // (hiredCount in job service can drift from failed transactions)
                    long actualHired = repo.findByJobIdAndStatus(app.getJobId(), ApplicationStatus.HIRED)
                            .stream().filter(a -> !a.getApplicationId().equals(app.getApplicationId())).count();

                    if (status == ApplicationStatus.OFFER && actualHired + currentOffers >= openings) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Cannot send offer: all " + openings + " slot(s) are already filled or reserved " +
                            "(" + actualHired + " hired, " + currentOffers + " offer(s) pending). " +
                            "Increase the hiring quota on the job to send more offers.");
                    }
                    if (status == ApplicationStatus.HIRED && actualHired >= openings) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Cannot hire: the quota of " + openings + " position(s) is already reached (" +
                            actualHired + " hired). Increase the hiring quota on the job to hire more candidates.");
                    }
                }
            } catch (ResponseStatusException e) { throw e; }
            catch (Exception e) {
                log.warn("[ATS] Quota check failed for job {}: {}", app.getJobId(), e.getMessage());
            }
        }

        app.setStatus(status);
        Application saved = repo.save(app);

        String jobTitle = null;
        try { var job = jobClient.getJob(app.getJobId()); jobTitle = job != null ? job.getTitle() : null; }
        catch (Exception ignored) {}

        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("jobId", app.getJobId().toString());
        payload.put("candidateUserId", app.getCandidateUserId());
        payload.put("oldStatus", oldStatus.name());
        payload.put("newStatus", status.name());
        payload.put("jobTitle", jobTitle != null ? jobTitle : "your applied job");
        payload.put("applicationId", id.toString());

        AppEventMessage evt = new AppEventMessage();
        evt.setEventType("APPLICATION_STATUS_UPDATE");
        evt.setProducer("application-microservice");
        AppEventMessage.Actor actor = new AppEventMessage.Actor();
        actor.setUserId(actorId);
        evt.setActor(actor);
        AppEventMessage.Target target = new AppEventMessage.Target();
        target.setType("APPLICATION");
        target.setId(id.toString());
        evt.setTarget(target);
        evt.setChanges(java.util.Map.of("status",
                java.util.Map.of("old", oldStatus.name(), "new", status.name())));
        evt.setPayload(payload);
        eventPublisher.publish("audit.application", evt);
        eventPublisher.publish("notify.application", evt);

        // No AI trigger here — analysis is done on APPLY + candidate edits in APPLIED

        // ATS: after commit, increment hired count and check quota
        // Uses @TransactionalEventListener(AFTER_COMMIT) so it runs in a new transaction
        // after the HIRED status is visible to all readers — avoids the rejectNonHiredForJob
        // seeing the candidate as still OFFER and incorrectly rejecting them.
        if (status == ApplicationStatus.HIRED && oldStatus != ApplicationStatus.HIRED) {
            springEventPublisher.publishEvent(new com.recrutment.application.events.HireCompletedEvent(
                    saved.getJobId(), jobTitle, 0, actorId
            ));
        }

        return toDto(saved);
    }

    @Transactional
    public int rejectNonHiredForJob(UUID jobId) {
        List<Application> apps = repo.findByJobId(jobId);
        int changed = 0;
        for (Application a : apps) {
            if (a.getStatus() == ApplicationStatus.HIRED) continue;
            if (a.getStatus() == ApplicationStatus.WITHDRAWN) continue;
            if (a.getStatus() == ApplicationStatus.REJECTED) continue;
            a.setStatus(ApplicationStatus.REJECTED);
            changed++;
        }
        repo.saveAll(apps);
        return changed;
    }

    @Transactional(readOnly = true)
    public PageResponse<ApplicationDto> listApplicationsPaged(UUID applicationId, UUID jobId,
                                                              ApplicationStatus status, String jobTitle,
                                                              String candidateName, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        String jt = jobTitle == null ? null : jobTitle.trim().toLowerCase();
        String cn = candidateName == null ? null : candidateName.trim().toLowerCase();
        boolean needsEnrichedFiltering = (jt != null && !jt.isBlank()) || (cn != null && !cn.isBlank());

        if (needsEnrichedFiltering) {
            List<Application> base;
            if (applicationId != null)                base = repo.findById(applicationId).map(List::of).orElseGet(List::of);
            else if (jobId != null && status != null) base = repo.findByJobIdAndStatus(jobId, status);
            else if (jobId != null)                   base = repo.findByJobId(jobId);
            else if (status != null)                  base = repo.findByStatus(status);
            else                                      base = repo.findAll();

            List<ApplicationDto> filtered = toDtoListRanked(base, jobId != null).stream()
                    .filter(d -> jt == null || jt.isBlank() || (d.getJobTitle() != null && d.getJobTitle().toLowerCase().contains(jt)))
                    .filter(d -> cn == null || cn.isBlank() || (d.getCandidateName() != null && d.getCandidateName().toLowerCase().contains(cn)))
                    .toList();
            int from = Math.min(safePage * safeSize, filtered.size());
            int to   = Math.min(from + safeSize, filtered.size());
            return new PageResponse<>(filtered.subList(from, to), safePage, safeSize, filtered.size(),
                    (int) Math.ceil(filtered.size() / (double) safeSize));
        }

        Page<Application> p;
        if (applicationId != null)                  p = new PageImpl<>(repo.findById(applicationId).map(List::of).orElseGet(List::of), pageable, 1);
        else if (jobId != null && status != null)   p = repo.findByJobIdAndStatus(jobId, status, pageable);
        else if (jobId != null)                     p = repo.findByJobId(jobId, pageable);
        else if (status != null)                    p = repo.findByStatus(status, pageable);
        else                                        p = repo.findAll(pageable);

        return new PageResponse<>(toDtoListRanked(p.getContent(), jobId != null),
                safePage, safeSize, p.getTotalElements(), p.getTotalPages());
    }

    public List<ApplicationStatus> getAllowedTransitions(ApplicationStatus current) {
        return ALLOWED_TRANSITIONS.getOrDefault(current, List.of());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRankForApplication(UUID applicationId) {
        Application app = repo.findById(applicationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Application not found: " + applicationId));

        List<Application> jobApps = repo.findByJobId(app.getJobId()).stream()
                .filter(a -> a.getStatus() != ApplicationStatus.WITHDRAWN)
                .toList();

        List<UUID> ids = jobApps.stream().map(Application::getApplicationId).toList();
        java.util.Map<UUID, Integer> scoreMap = cvAnalysisRepo.findByApplicationIdIn(ids).stream()
                .filter(c -> c.getSemanticMatch() != null && c.getSemanticMatch().getJobFitScore() != null)
                .collect(java.util.stream.Collectors.toMap(
                        com.recrutment.application.entities.CvAnalysis::getApplicationId,
                        c -> c.getSemanticMatch().getJobFitScore()
                ));

        List<UUID> ranked = jobApps.stream()
                .sorted(java.util.Comparator.comparingInt(
                        (Application a) -> -(scoreMap.getOrDefault(a.getApplicationId(), 0))
                ))
                .map(Application::getApplicationId)
                .toList();

        int rank = ranked.indexOf(applicationId) + 1;

        return Map.of("rank", rank, "total", jobApps.size());
    }

    @Transactional
    public Map<String, Object> shortlistByScore(UUID jobId, int threshold) {
        List<Application> applied = repo.findByJobIdAndStatus(jobId, ApplicationStatus.APPLIED);
        if (applied.isEmpty()) return Map.of("shortlisted", 0, "skipped", 0, "threshold", threshold);

        List<UUID> ids = applied.stream().map(Application::getApplicationId).toList();
        java.util.Map<UUID, Integer> scoreMap = cvAnalysisRepo.findByApplicationIdIn(ids).stream()
                .filter(c -> c.getSemanticMatch() != null && c.getSemanticMatch().getJobFitScore() != null)
                .collect(java.util.stream.Collectors.toMap(
                        com.recrutment.application.entities.CvAnalysis::getApplicationId,
                        c -> c.getSemanticMatch().getJobFitScore()
                ));

        int shortlisted = 0;
        int skipped = 0;
        for (Application app : applied) {
            Integer score = scoreMap.get(app.getApplicationId());
            if (score != null && score >= threshold) {
                app.setStatus(ApplicationStatus.UNDER_REVIEW);
                shortlisted++;
            } else {
                skipped++;
            }
        }
        if (shortlisted > 0) repo.saveAll(applied);
        return Map.of("shortlisted", shortlisted, "skipped", skipped, "threshold", threshold);
    }

    @Transactional
    public void deleteApplicationsForJob(UUID jobId) {
        List<Application> apps = repo.findByJobId(jobId);
        if (apps.isEmpty()) return;
        List<UUID> appIds = apps.stream().map(Application::getApplicationId).toList();
        cvAnalysisRepo.deleteByApplicationIdIn(appIds);
        repo.deleteAll(apps);
    }
}