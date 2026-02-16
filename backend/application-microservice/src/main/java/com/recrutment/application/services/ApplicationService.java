package com.recrutment.application.services;

import com.recrutment.application.clients.JobClient;
import com.recrutment.application.clients.UserClient;
import com.recrutment.application.dto.ApplicationDto;
import com.recrutment.application.dto.PageResponse;
import com.recrutment.application.entities.Application;
import com.recrutment.application.enums.ApplicationStatus;
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


import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ApplicationService {

    private final ApplicationRepo repo;
    private final JobClient jobClient;
    private final UserClient userClient;

    // ✅ Enrich DTO with jobTitle + candidateName
    private ApplicationDto toDto(Application a) {
        String jobTitle = null;
        String candidateName = null;

        // job title
        try {
            var job = jobClient.getJob(a.getJobId());
            jobTitle = (job != null ? job.getTitle() : null);
        } catch (Exception ignored) {}

        // candidate name
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
            return toDto(repo.save(app));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You already applied to this job.");
        }
    }


    /**
     * ✅ Search:
     * - applicationId (exact)
     * - status (exact)
     * - jobTitle (contains, ignore-case)  -> done by enrichment + filtering
     * - candidateName (contains, ignore-case) -> done by enrichment + filtering
     */
    @Transactional(readOnly = true)
    public List<ApplicationDto> listApplications(
            UUID applicationId,
            ApplicationStatus status,
            String jobTitle,
            String candidateName
    ) {
        // 1) base fetch from DB (ONLY by DB fields)
        List<Application> base;

        if (applicationId != null) {
            base = repo.findById(applicationId).map(List::of).orElseGet(List::of);
        } else if (status != null) {
            base = repo.findByStatus(status);
        } else {
            base = repo.findAll();
        }

        // 2) enrich -> DTO
        List<ApplicationDto> dtos = base.stream().map(this::toDto).toList();

        // 3) filter by jobTitle / candidateName (strings from other services)
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

        // ✅ must belong to candidate
        if (!candidateUserId.equals(app.getCandidateUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed.");
        }

        // ✅ only allowed while APPLIED
        if (app.getStatus() != ApplicationStatus.APPLIED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "You can update the application only while status is APPLIED.");
        }

        // ✅ update github (if provided)
        if (githubUrl != null && !githubUrl.trim().isBlank()) {
            app.setGithubUrl(githubUrl.trim());
        }

        // ✅ update CV (if provided)
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
    public ApplicationDto updateStatus(UUID id, ApplicationStatus status) {

        if (status == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status is required.");
        }

        Application app = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Application not found: " + id
                ));

        app.setStatus(status);

        return toDto(repo.save(app));
    }

    @Transactional(readOnly = true)
    public PageResponse<ApplicationDto> listApplicationsPaged(
            UUID applicationId,
            ApplicationStatus status,
            String jobTitle,
            String candidateName,
            int page,
            int size
    ) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50); // cap to 50
        Pageable pageable = PageRequest.of(safePage, safeSize);

        String jt = jobTitle == null ? null : jobTitle.trim().toLowerCase();
        String cn = candidateName == null ? null : candidateName.trim().toLowerCase();

        boolean needsEnrichedFiltering =
                (jt != null && !jt.isBlank()) || (cn != null && !cn.isBlank());

        // If we're filtering by jobTitle/candidateName (enriched fields),
        // we must fetch a bigger set then filter then paginate.
        if (needsEnrichedFiltering) {
            List<Application> base;

            if (applicationId != null) {
                base = repo.findById(applicationId).map(List::of).orElseGet(List::of);
            } else if (status != null) {
                base = repo.findByStatus(status);
            } else {
                base = repo.findAll();
            }

            List<ApplicationDto> filtered = base.stream()
                    .map(this::toDto)
                    .filter(d -> jt == null || jt.isBlank()
                            || (d.getJobTitle() != null && d.getJobTitle().toLowerCase().contains(jt)))
                    .filter(d -> cn == null || cn.isBlank()
                            || (d.getCandidateName() != null && d.getCandidateName().toLowerCase().contains(cn)))
                    .toList();

            int from = Math.min(safePage * safeSize, filtered.size());
            int to = Math.min(from + safeSize, filtered.size());

            List<ApplicationDto> content = filtered.subList(from, to);
            long total = filtered.size();
            int totalPages = (int) Math.ceil(total / (double) safeSize);

            return new PageResponse<>(content, safePage, safeSize, total, totalPages);
        }

        // Otherwise, we can paginate directly in DB
        Page<Application> p;

        if (applicationId != null) {
            List<Application> single = repo.findById(applicationId).map(List::of).orElseGet(List::of);
            p = new PageImpl<>(single, pageable, single.size());
        } else if (status != null) {
            // ✅ best: add repo method findByStatus(status, pageable)
            // if you don't have it yet, add it (shown below)
            p = repo.findByStatus(status, pageable);
        } else {
            p = repo.findAll(pageable);
        }

        List<ApplicationDto> content = p.getContent().stream().map(this::toDto).toList();
        return new PageResponse<>(content, safePage, safeSize, p.getTotalElements(), p.getTotalPages());
    }

}
