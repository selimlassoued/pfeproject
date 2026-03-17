package com.recrutment.application.services;

import com.recrutment.application.clients.JobClient;
import com.recrutment.application.clients.UserClient;
import com.recrutment.application.dto.ApplicationDto;
import com.recrutment.application.dto.PageResponse;
import com.recrutment.application.entities.Application;
import com.recrutment.application.enums.ApplicationStatus;
import com.recrutment.application.messaging.AppEventMessage;
import com.recrutment.application.messaging.AppEventPublisher;
import com.recrutment.application.repos.ApplicationRepo;
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

import com.recrutment.application.services.CvAnalysisService;


import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ApplicationService {
    private final CvAnalysisService cvAnalysisService;
    private final AppEventPublisher eventPublisher;

    private final ApplicationRepo repo;
    private final JobClient jobClient;
    private final UserClient userClient;

    private ApplicationDto toDto(Application a) {
        String jobTitle = null;
        String candidateName = null;

        try {
            var job = jobClient.getJob(a.getJobId());
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

        return new ApplicationDto(
                a.getApplicationId(),
                a.getJobId(),
                a.getCandidateUserId(),
                a.getGithubUrl(),
                a.getStatus(),
                a.getAppliedAt(),
                a.getCvFileName(),
                a.getCvContentType(),
                jobTitle,
                candidateName
        );
    }

    public ApplicationDto apply(UUID jobId, String candidateUserId, String githubUrl, MultipartFile cv) throws IOException {

        if (cv == null || cv.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CV file is required.");
        }

        String ct = cv.getContentType();
        boolean isPdf = "application/pdf".equalsIgnoreCase(ct)
                || (cv.getOriginalFilename() != null && cv.getOriginalFilename().toLowerCase().endsWith(".pdf"));

        if (!isPdf) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only PDF is allowed.");
        }

        repo.findByJobIdAndCandidateUserId(jobId, candidateUserId).ifPresent(a -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You already applied to this job.");
        });

        Application app = Application.builder()
                .jobId(jobId)
                .candidateUserId(candidateUserId)
                .githubUrl(githubUrl)
                .status(ApplicationStatus.APPLIED)
                .appliedAt(Instant.now())
                .cvFile(cv.getBytes())
                .cvFileName(cv.getOriginalFilename() == null ? "cv.pdf" : cv.getOriginalFilename())
                .cvContentType("application/pdf")
                .build();

        try {
            Application saved = repo.save(app);
            // Trigger async CV analysis (non-blocking)
            cvAnalysisService.analyzeAsync(saved);
            return toDto(saved);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You already applied to this job.");
        }
    }


    @Transactional(readOnly = true)
    public List<ApplicationDto> listApplications(
            UUID applicationId,
            ApplicationStatus status,
            String jobTitle,
            String candidateName
    ) {
        List<Application> base;

        if (applicationId != null) {
            base = repo.findById(applicationId).map(List::of).orElseGet(List::of);
        } else if (status != null) {
            base = repo.findByStatus(status);
        } else {
            base = repo.findAll();
        }

        List<ApplicationDto> dtos = base.stream().map(this::toDto).toList();

        String jt = jobTitle == null ? null : jobTitle.trim().toLowerCase();
        String cn = candidateName == null ? null : candidateName.trim().toLowerCase();

        return dtos.stream()
                .filter(d -> jt == null || jt.isBlank()
                        || (d.getJobTitle() != null && d.getJobTitle().toLowerCase().contains(jt)))
                .filter(d -> cn == null || cn.isBlank()
                        || (d.getCandidateName() != null && d.getCandidateName().toLowerCase().contains(cn)))
                .toList();
    }

    @Transactional(readOnly = true)
    public ApplicationDto getOne(UUID id) {
        Application app = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found: " + id));
        return toDto(app);
    }
    @Transactional(readOnly = true)
    public ApplicationDto getMyApplicationByJob(UUID jobId, String candidateUserId) {
        Application app = repo.findByJobIdAndCandidateUserId(jobId, candidateUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No application for this job."));
        return toDto(app);
    }

    @Transactional(readOnly = true)
    public List<ApplicationDto> listMyApplications(String candidateUserId) {
        return repo.findByCandidateUserId(candidateUserId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> getCandidateUserIdsByJob(UUID jobId) {
        return repo.findByJobId(jobId).stream()
                .map(Application::getCandidateUserId)
                .distinct()
                .toList();
    }

    @Transactional(readOnly = true)
    public ApplicationDto getMyApplication(UUID id, String candidateUserId) {
        Application app = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found: " + id));

        if (!candidateUserId.equals(app.getCandidateUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed.");
        }

        return toDto(app);
    }

    @Transactional
    public ApplicationDto updateMyApplication(
            UUID id,
            String candidateUserId,
            String githubUrl,
            MultipartFile cv
    ) throws IOException {

        Application app = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found: " + id));

        if (!candidateUserId.equals(app.getCandidateUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed.");
        }

        if (app.getStatus() != ApplicationStatus.APPLIED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "You can update the application only while status is APPLIED.");
        }

        if (githubUrl != null && !githubUrl.trim().isBlank()) {
            app.setGithubUrl(githubUrl.trim());
        }

        if (cv != null && !cv.isEmpty()) {

            String ct = cv.getContentType();
            boolean isPdf = "application/pdf".equalsIgnoreCase(ct)
                    || (cv.getOriginalFilename() != null && cv.getOriginalFilename().toLowerCase().endsWith(".pdf"));

            if (!isPdf) {
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only PDF is allowed.");
            }

            app.setCvFile(cv.getBytes());
            app.setCvFileName(cv.getOriginalFilename() == null ? "cv.pdf" : cv.getOriginalFilename());
            app.setCvContentType("application/pdf");
        }

        return toDto(repo.save(app));
    }
    @Transactional
    public ApplicationDto updateStatus(UUID id, ApplicationStatus status, String actorUserId) {

        if (status == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status is required.");
        }
        String actorId = (actorUserId != null && !actorUserId.isBlank()) ? actorUserId : "SYSTEM";

        Application app = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Application not found: " + id
                ));

        ApplicationStatus oldStatus = app.getStatus();
        UUID jobId = app.getJobId();
        String candidateUserId = app.getCandidateUserId();

        app.setStatus(status);
        Application saved = repo.save(app);
        ApplicationDto result = toDto(saved);

        // build changes diff
        Map<String, Object> changes = java.util.Map.of(
                "status", java.util.Map.of(
                        "old", oldStatus.name(),
                        "new", status.name()
                )
        );

        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("jobId", jobId.toString());
        payload.put("candidateUserId", candidateUserId);
        payload.put("oldStatus", oldStatus.name());
        payload.put("newStatus", status.name());

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

        evt.setChanges(changes);
        evt.setPayload(payload);

        // audit event
        eventPublisher.publish("audit.application", evt);
        eventPublisher.publish("notify.application", evt);
        // later: also publish notify.application.status with same payload

        return result;
    }

    @Transactional(readOnly = true)
    public PageResponse<ApplicationDto> listApplicationsPaged(
            UUID applicationId,
            UUID jobId,           // ← ADD THIS
            ApplicationStatus status,
            String jobTitle,
            String candidateName,
            int page,
            int size
    ) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);
        Pageable pageable = PageRequest.of(safePage, safeSize);

        String jt = jobTitle == null ? null : jobTitle.trim().toLowerCase();
        String cn = candidateName == null ? null : candidateName.trim().toLowerCase();
        boolean needsEnrichedFiltering = (jt != null && !jt.isBlank()) || (cn != null && !cn.isBlank());

        if (needsEnrichedFiltering) {
            List<Application> base;
            if (applicationId != null)      base = repo.findById(applicationId).map(List::of).orElseGet(List::of);
            else if (jobId != null && status != null) base = repo.findByJobIdAndStatus(jobId, status);
            else if (jobId != null)         base = repo.findByJobId(jobId);
            else if (status != null)        base = repo.findByStatus(status);
            else                            base = repo.findAll();

            List<ApplicationDto> filtered = base.stream().map(this::toDto)
                    .filter(d -> jt == null || jt.isBlank() || (d.getJobTitle() != null && d.getJobTitle().toLowerCase().contains(jt)))
                    .filter(d -> cn == null || cn.isBlank() || (d.getCandidateName() != null && d.getCandidateName().toLowerCase().contains(cn)))
                    .toList();

            int from = Math.min(safePage * safeSize, filtered.size());
            int to   = Math.min(from + safeSize, filtered.size());
            return new PageResponse<>(filtered.subList(from, to), safePage, safeSize, filtered.size(),
                    (int) Math.ceil(filtered.size() / (double) safeSize));
        }

        // DB-level pagination
        Page<Application> p;
        if      (applicationId != null)             p = new PageImpl<>(repo.findById(applicationId).map(List::of).orElseGet(List::of), pageable, 1);
        else if (jobId != null && status != null)   p = repo.findByJobIdAndStatus(jobId, status, pageable);
        else if (jobId != null)                     p = repo.findByJobId(jobId, pageable);
        else if (status != null)                    p = repo.findByStatus(status, pageable);
        else                                        p = repo.findAll(pageable);

        return new PageResponse<>(p.getContent().stream().map(this::toDto).toList(),
                safePage, safeSize, p.getTotalElements(), p.getTotalPages());
    }

}
