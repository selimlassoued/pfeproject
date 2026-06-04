package com.zaina.jobmicroservice.services;

import com.zaina.jobmicroservice.domain.entities.JobOffer;
import com.zaina.jobmicroservice.domain.entities.JobRequirement;
import com.zaina.jobmicroservice.dto.JobEmbeddingDto;
import com.zaina.jobmicroservice.dto.JobOfferDto;
import com.zaina.jobmicroservice.dto.JobRequirementDto;
import com.zaina.jobmicroservice.dto.PageResponse;
import com.zaina.jobmicroservice.dto.RequirementEmbeddingDto;
import com.zaina.jobmicroservice.repos.JobRequirementRepo;
import com.zaina.jobmicroservice.domain.enums.EmploymentType;
import com.zaina.jobmicroservice.domain.enums.JobStatus;
import com.zaina.jobmicroservice.messaging.AppEventMessage;
import com.zaina.jobmicroservice.messaging.AppEventPublisher;
import com.zaina.jobmicroservice.repos.JobOfferRepo;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@AllArgsConstructor
@Transactional
public class JobOfferServiceImpl implements JobOfferService {

    private final AppEventPublisher eventPublisher;
    private final JobOfferRepo jobOfferRepo;
    private final JobRequirementRepo jobRequirementRepo;
    private final com.zaina.jobmicroservice.clients.ApplicationClient applicationClient;

    private boolean hasApplications(UUID jobId) {
        return applicationClient.hasApplications(jobId);
    }


    private static JobRequirementDto toDtoReq(JobRequirement r) {
        return new JobRequirementDto(
                r.getId(),
                r.getCategory(),
                r.getDescription(),
                r.getWeight(),
                r.getMinYears(),
                r.getMaxYears()
        );
    }

    private static JobOfferDto toDto(JobOffer j) {
        return new JobOfferDto(
                j.getId(),
                j.getRefNumber(),
                j.getTitle(),
                j.getDescription(),
                j.getLocation(),
                j.getWorkArrangement(),
                j.getDomain(),
                j.getMinSalary(),
                j.getMaxSalary(),
                j.getOpenings(),
                j.getHiredCount(),
                j.getEmploymentType(),
                j.getJobStatus(),
                j.getSkillsWeight(),
                j.getSemanticWeight(),
                j.getExperienceWeight(),
                j.getSeniorityWeight(),
                j.getRequirements() == null
                        ? List.of()
                        : j.getRequirements().stream().map(JobOfferServiceImpl::toDtoReq).toList(),
                j.getCreatedAt()
        );
    }

    private static double normalizeWeight(Double w, double defaultVal) {
        return (w != null && w >= 0) ? w : defaultVal;
    }

    @Override
    @Transactional(readOnly = true)
    public JobOfferDto getJobOfferById(UUID id) {
        JobOffer jobOffer = jobOfferRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("JobOffer not found: " + id));
        return toDto(jobOffer);
    }

    @Override
    @Transactional(readOnly = true)
    public List<JobOfferDto> getJobOffers() {
        return jobOfferRepo.findAll().stream().map(JobOfferServiceImpl::toDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<JobOfferDto> searchJobs(
            String query,
            EmploymentType employmentType,
            JobStatus jobStatus,
            Integer minSalary,
            Integer maxSalary,
            Pageable pageable) {

        Page<JobOffer> page = jobOfferRepo.searchAndFilter(
                query,
                employmentType,
                jobStatus,
                minSalary,
                maxSalary,
                pageable
        );

        return PageResponse.<JobOfferDto>builder()
                .content(page.getContent().stream().map(JobOfferServiceImpl::toDto).toList())
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .hasNext(page.hasNext())
                .hasPrevious(page.hasPrevious())
                .build();
    }

    @Override
    public JobOfferDto createJobOffer(JobOfferDto dto, String actorUserId) {
        String actor = (actorUserId != null && !actorUserId.isBlank()) ? actorUserId : "SYSTEM";

        JobOffer entity = JobOffer.builder()
                .title(dto.getTitle())
                .description(dto.getDescription())
                .location(dto.getLocation())
                .workArrangement(dto.getWorkArrangement())
                .domain(dto.getDomain())
                .minSalary(dto.getMinSalary())
                .maxSalary(dto.getMaxSalary())
                .openings(dto.getOpenings() == null || dto.getOpenings() < 1 ? 1 : dto.getOpenings())
                .hiredCount(0)
                .employmentType(dto.getEmploymentType())
                .jobStatus(dto.getJobStatus())
                .skillsWeight(normalizeWeight(dto.getSkillsWeight(), 0.40))
                .semanticWeight(normalizeWeight(dto.getSemanticWeight(), 0.35))
                .experienceWeight(normalizeWeight(dto.getExperienceWeight(), 0.15))
                .seniorityWeight(normalizeWeight(dto.getSeniorityWeight(), 0.10))
                .build();

        if (dto.getRequirements() != null) {
            for (JobRequirementDto r : dto.getRequirements()) {
                JobRequirement req = JobRequirement.builder()
                        .category(r.getCategory())
                        .description(r.getDescription())
                        .weight(r.getWeight())
                        .minYears(r.getMinYears())
                        .maxYears(r.getMaxYears())
                        .build();
                entity.addRequirement(req);
            }
        }

        // first save to get the id
        JobOffer saved = jobOfferRepo.save(entity);

        // generate unique sequential refNumber after first save
        int seq = jobOfferRepo.nextRefSequence();
        saved.setRefNumber(String.format("JOB-%05d", seq));
        saved = jobOfferRepo.save(saved);

        JobOfferDto result = toDto(saved);

        AppEventMessage evt = new AppEventMessage();
        evt.setEventType("JOB_CREATED");
        evt.setProducer("job-microservice");
        AppEventMessage.Actor actorObj = new AppEventMessage.Actor();
        actorObj.setUserId(actor);
        evt.setActor(actorObj);
        AppEventMessage.Target target = new AppEventMessage.Target();
        target.setType("JOB");
        target.setId(saved.getId().toString());
        evt.setTarget(target);
        eventPublisher.publish("audit.job", evt);

        return result;
    }

    @Override
    public JobOfferDto updateJobOffer(UUID id, JobOfferDto dto, String reason, String actorUserId) {
        String actor = (actorUserId != null && !actorUserId.isBlank()) ? actorUserId : "SYSTEM";
        JobOffer existing = jobOfferRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("JobOffer not found: " + id));

        if (existing.getJobStatus() == JobStatus.CLOSED) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "Closed jobs cannot be edited. Duplicate it as a new job instead."
            );
        }

        if (existing.getJobStatus() == JobStatus.PUBLISHED && hasApplications(id)) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "This job already has applications. It can no longer be edited."
            );
        }

        String oldTitle          = existing.getTitle();
        String oldDescription    = existing.getDescription();
        String oldLocation       = existing.getLocation();
        Integer oldMinSalary     = existing.getMinSalary();
        Integer oldMaxSalary     = existing.getMaxSalary();
        Integer oldOpenings      = existing.getOpenings();
        Integer oldHiredCount    = existing.getHiredCount();
        var oldEmploymentType    = existing.getEmploymentType();
        var oldJobStatus         = existing.getJobStatus();

        existing.setTitle(dto.getTitle());
        existing.setDescription(dto.getDescription());
        existing.setLocation(dto.getLocation());
        existing.setWorkArrangement(dto.getWorkArrangement());
        existing.setDomain(dto.getDomain());
        existing.setMinSalary(dto.getMinSalary());
        existing.setMaxSalary(dto.getMaxSalary());
        // A closed job cannot be reopened — create a new job instead
        if (existing.getJobStatus() == JobStatus.CLOSED
                && dto.getJobStatus() == JobStatus.PUBLISHED) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "A closed job cannot be republished. Duplicate it as a new job offer instead."
            );
        }

        // Openings locked once PUBLISHED or CLOSED — set upfront in DRAFT only
        boolean isLocked = existing.getJobStatus() == JobStatus.PUBLISHED
                        || existing.getJobStatus() == JobStatus.CLOSED;
        if (!isLocked && dto.getOpenings() != null && dto.getOpenings() >= 1) {
            existing.setOpenings(dto.getOpenings());
        } else if (isLocked && dto.getOpenings() != null && !dto.getOpenings().equals(existing.getOpenings())) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "Hiring quota cannot be changed once the job is " + existing.getJobStatus() +
                ". Set the quota before publishing."
            );
        }
        if (dto.getHiredCount() != null && dto.getHiredCount() >= 0) existing.setHiredCount(dto.getHiredCount());
        existing.setEmploymentType(dto.getEmploymentType());
        existing.setJobStatus(dto.getJobStatus());
        existing.setSkillsWeight(normalizeWeight(dto.getSkillsWeight(), existing.getSkillsWeight()));
        existing.setSemanticWeight(normalizeWeight(dto.getSemanticWeight(), existing.getSemanticWeight()));
        existing.setExperienceWeight(normalizeWeight(dto.getExperienceWeight(), existing.getExperienceWeight()));
        existing.setSeniorityWeight(normalizeWeight(dto.getSeniorityWeight(), existing.getSeniorityWeight()));
        // refNumber is updatable=false — never touched on update

        if (existing.getRequirements() != null) existing.getRequirements().clear();
        if (dto.getRequirements() != null) {
            for (JobRequirementDto r : dto.getRequirements()) {
                JobRequirement req = JobRequirement.builder()
                        .category(r.getCategory())
                        .description(r.getDescription())
                        .weight(r.getWeight())
                        .minYears(r.getMinYears())
                        .maxYears(r.getMaxYears())
                        .build();
                existing.addRequirement(req);
            }
        }

        JobOffer saved = jobOfferRepo.save(existing);
        JobOfferDto result = toDto(saved);

        Map<String, Object> changes = new java.util.HashMap<>();
        if (!java.util.Objects.equals(oldTitle,          saved.getTitle()))          changes.put("title",          Map.of("old", oldTitle,          "new", saved.getTitle()));
        if (!java.util.Objects.equals(oldDescription,    saved.getDescription()))    changes.put("description",    Map.of("old", oldDescription,    "new", saved.getDescription()));
        if (!java.util.Objects.equals(oldLocation,       saved.getLocation()))       changes.put("location",       Map.of("old", oldLocation,       "new", saved.getLocation()));
        if (!java.util.Objects.equals(oldMinSalary,      saved.getMinSalary()))      changes.put("minSalary",      Map.of("old", oldMinSalary,      "new", saved.getMinSalary()));
        if (!java.util.Objects.equals(oldMaxSalary,      saved.getMaxSalary()))      changes.put("maxSalary",      Map.of("old", oldMaxSalary,      "new", saved.getMaxSalary()));
        if (!java.util.Objects.equals(oldOpenings,       saved.getOpenings()))       changes.put("openings",       Map.of("old", oldOpenings,       "new", saved.getOpenings()));
        if (!java.util.Objects.equals(oldHiredCount,     saved.getHiredCount()))     changes.put("hiredCount",     Map.of("old", oldHiredCount,     "new", saved.getHiredCount()));
        if (!java.util.Objects.equals(oldEmploymentType, saved.getEmploymentType())) changes.put("employmentType", Map.of("old", oldEmploymentType, "new", saved.getEmploymentType()));
        if (!java.util.Objects.equals(oldJobStatus,      saved.getJobStatus()))      changes.put("jobStatus",      Map.of("old", oldJobStatus,      "new", saved.getJobStatus()));

        AppEventMessage evt = new AppEventMessage();
        evt.setEventType("JOB_UPDATED");
        evt.setProducer("job-microservice");
        AppEventMessage.Actor actorObj = new AppEventMessage.Actor();
        actorObj.setUserId(actor);
        evt.setActor(actorObj);
        AppEventMessage.Target target = new AppEventMessage.Target();
        target.setType("JOB");
        target.setId(id.toString());
        evt.setTarget(target);
        evt.setChanges(changes);
        if (reason != null && !reason.isBlank()) evt.setReason(reason);
        evt.setPayload(Map.of("jobTitle", saved.getTitle()));
        eventPublisher.publish("audit.job", evt);

        // Notify candidates only when candidate-visible fields change on a PUBLISHED job.
        // AI scoring weights, openings, hiredCount are internal — candidates never see them.
        java.util.Set<String> candidateVisibleFields = java.util.Set.of(
                "title", "description", "location", "minSalary", "maxSalary", "employmentType"
        );
        boolean hasVisibleChange = changes.keySet().stream().anyMatch(candidateVisibleFields::contains);
        boolean isPublished = saved.getJobStatus() == JobStatus.PUBLISHED;
        if (hasVisibleChange && isPublished) {
            eventPublisher.publish("notify.job", evt);
        }

        return result;
    }

    @Override
    public void deleteJobOffer(UUID id) {
        if (!jobOfferRepo.existsById(id)) throw new RuntimeException("JobOffer not found: " + id);
        jobOfferRepo.deleteById(id);
    }

    @Override
    public JobOfferDto incrementHiredCount(UUID jobId) {
        JobOffer job = jobOfferRepo.findById(jobId)
                .orElseThrow(() -> new RuntimeException("JobOffer not found: " + jobId));
        if (job.getHiredCount() == null) job.setHiredCount(0);
        job.setHiredCount(job.getHiredCount() + 1);
        JobOffer saved = jobOfferRepo.save(job);

        // Notify when quota reached (recruiter can close manually)
        if (saved.getOpenings() != null && saved.getHiredCount() != null && saved.getHiredCount() >= saved.getOpenings()) {
            AppEventMessage evt = new AppEventMessage();
            evt.setEventType("JOB_QUOTA_REACHED");
            evt.setProducer("job-microservice");
            AppEventMessage.Target target = new AppEventMessage.Target();
            target.setType("JOB");
            target.setId(jobId.toString());
            evt.setTarget(target);
            evt.setPayload(Map.of(
                    "jobId", jobId.toString(),
                    "jobTitle", saved.getTitle(),
                    "openings", saved.getOpenings(),
                    "hiredCount", saved.getHiredCount()
            ));
            eventPublisher.publish("notify.job", evt);
            // No audit entry here — JOB_CLOSED (published by closeJob) covers the audit trail
        }

        return toDto(saved);
    }

    @Override
    public JobOfferDto closeJob(UUID jobId, String actorUserId, String reason) {
        String actor = (actorUserId != null && !actorUserId.isBlank()) ? actorUserId : "SYSTEM";
        JobOffer job = jobOfferRepo.findById(jobId)
                .orElseThrow(() -> new RuntimeException("JobOffer not found: " + jobId));
        job.setJobStatus(JobStatus.CLOSED);
        JobOffer saved = jobOfferRepo.save(job);

        // Reject all non-HIRED applications for this job
        try {
            applicationClient.rejectNonHiredForJob(jobId);
        } catch (Exception ignored) {}

        AppEventMessage evt = new AppEventMessage();
        evt.setEventType("JOB_CLOSED");
        evt.setProducer("job-microservice");
        AppEventMessage.Actor actorObj = new AppEventMessage.Actor();
        actorObj.setUserId(actor);
        evt.setActor(actorObj);
        AppEventMessage.Target target = new AppEventMessage.Target();
        target.setType("JOB");
        target.setId(jobId.toString());
        evt.setTarget(target);

        // Include reason in the audit trail:
        //  - quota reached  → automatic system message
        //  - manual close   → the reason the recruiter typed (required by the UI)
        boolean quotaReached = saved.getOpenings() != null && saved.getHiredCount() != null
                && saved.getHiredCount() >= saved.getOpenings();
        String auditReason;
        if (quotaReached) {
            auditReason = "Hiring quota reached (" + saved.getHiredCount() + "/" + saved.getOpenings()
                    + ") — position filled automatically";
        } else if (reason != null && !reason.isBlank()) {
            auditReason = reason.trim();
        } else {
            auditReason = "Closed manually (no reason provided)";
        }
        evt.setReason(auditReason);
        evt.setPayload(Map.of(
                "jobId",    jobId.toString(),
                "jobTitle", saved.getTitle(),
                "reason",   auditReason
        ));
        eventPublisher.publish("audit.job", evt);
        eventPublisher.publish("notify.job", evt);

        return toDto(saved);
    }

    // ── Embedding cache ──────────────────────────────────────────────────────
    // The Python cv-parser-service is the producer of these vectors. It calls
    // GET on first match for a job to look for a cached vector, and PUTs the
    // freshly-computed one back if none exists. Subsequent applicants for the
    // same job get the cached vector and avoid the Ollama round-trip entirely.

    @Override
    @Transactional(readOnly = true)
    public JobEmbeddingDto getEmbedding(UUID jobId) {
        JobOffer job = jobOfferRepo.findById(jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found: " + jobId));
        String raw = job.getEmbedding();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return new JobEmbeddingDto(parseVectorLiteral(raw), job.getEmbeddingModel());
    }

    @Override
    public void saveEmbedding(UUID jobId, JobEmbeddingDto dto) {
        if (dto == null || dto.getEmbedding() == null || dto.getEmbedding().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Embedding payload is empty");
        }
        if (dto.getEmbedding().size() != 768) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Embedding must have 768 dimensions, got " + dto.getEmbedding().size());
        }
        if (!jobOfferRepo.existsById(jobId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found: " + jobId);
        }
        // Native UPDATE with ::vector cast — JPA's generic UPDATE can't bind a
        // varchar parameter to a vector column. Bypassing JPA dirty-checking
        // for this column is intentional (see JobOffer entity for context).
        jobOfferRepo.updateEmbedding(jobId, toVectorLiteral(dto.getEmbedding()), dto.getModel());
    }

    @Override
    @Transactional(readOnly = true)
    public List<RequirementEmbeddingDto> getRequirementEmbeddings(UUID jobId) {
        List<com.zaina.jobmicroservice.domain.entities.JobRequirement> reqs =
                jobRequirementRepo.findByJobOffer_Id(jobId);
        List<RequirementEmbeddingDto> out = new ArrayList<>();
        for (var r : reqs) {
            String raw = r.getEmbedding();
            if (raw == null || raw.isBlank()) continue;
            out.add(new RequirementEmbeddingDto(r.getId(), parseVectorLiteral(raw), r.getEmbeddingModel()));
        }
        return out;
    }

    @Override
    public void saveRequirementEmbedding(UUID jobId, UUID requirementId, RequirementEmbeddingDto dto) {
        if (dto == null || dto.getEmbedding() == null || dto.getEmbedding().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Embedding payload is empty");
        }
        if (dto.getEmbedding().size() != 768) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Embedding must have 768 dimensions, got " + dto.getEmbedding().size());
        }
        var req = jobRequirementRepo.findById(requirementId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Requirement not found: " + requirementId));
        // Defensive ownership check — reject if the requirement doesn't belong
        // to the job in the URL, prevents cross-job tampering.
        if (req.getJobOffer() == null || !jobId.equals(req.getJobOffer().getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Requirement " + requirementId + " is not on job " + jobId);
        }
        jobRequirementRepo.updateEmbedding(requirementId, toVectorLiteral(dto.getEmbedding()), dto.getModel());
    }

    /** Convert a Java float list to pgvector's "[v0,v1,...]" string literal. */
    private static String toVectorLiteral(List<Float> v) {
        StringBuilder sb = new StringBuilder(v.size() * 12);
        sb.append('[');
        for (int i = 0; i < v.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(v.get(i));
        }
        sb.append(']');
        return sb.toString();
    }

    /** Parse pgvector's "[v0,v1,...]" string literal back into a Java float list. */
    private static List<Float> parseVectorLiteral(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("[")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("]"))   trimmed = trimmed.substring(0, trimmed.length() - 1);
        String[] parts = trimmed.split(",");
        List<Float> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            String s = p.trim();
            if (!s.isEmpty()) out.add(Float.parseFloat(s));
        }
        return out;
    }
}