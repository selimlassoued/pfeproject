package com.zaina.jobmicroservice.services;

import com.zaina.jobmicroservice.domain.entities.JobOffer;
import com.zaina.jobmicroservice.domain.entities.JobRequirement;
import com.zaina.jobmicroservice.dto.JobOfferDto;
import com.zaina.jobmicroservice.dto.JobRequirementDto;
import com.zaina.jobmicroservice.dto.PageResponse;
import com.zaina.jobmicroservice.domain.enums.EmploymentType;
import com.zaina.jobmicroservice.domain.enums.JobStatus;
import com.zaina.jobmicroservice.messaging.AppEventMessage;
import com.zaina.jobmicroservice.messaging.AppEventPublisher;
import com.zaina.jobmicroservice.repos.JobOfferRepo;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@AllArgsConstructor
@Transactional
public class JobOfferServiceImpl implements JobOfferService {

    private final AppEventPublisher eventPublisher;
    private final JobOfferRepo jobOfferRepo;

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
                j.getRefNumber(),   // ← mapped
                j.getTitle(),
                j.getDescription(),
                j.getLocation(),
                j.getMinSalary(),
                j.getMaxSalary(),
                j.getEmploymentType(),
                j.getJobStatus(),
                j.getRequirements() == null
                        ? List.of()
                        : j.getRequirements().stream().map(JobOfferServiceImpl::toDtoReq).toList()
        );
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
                .minSalary(dto.getMinSalary())
                .maxSalary(dto.getMaxSalary())
                .employmentType(dto.getEmploymentType())
                .jobStatus(dto.getJobStatus())
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

        String oldTitle          = existing.getTitle();
        String oldDescription    = existing.getDescription();
        String oldLocation       = existing.getLocation();
        Integer oldMinSalary     = existing.getMinSalary();
        Integer oldMaxSalary     = existing.getMaxSalary();
        var oldEmploymentType    = existing.getEmploymentType();
        var oldJobStatus         = existing.getJobStatus();

        existing.setTitle(dto.getTitle());
        existing.setDescription(dto.getDescription());
        existing.setLocation(dto.getLocation());
        existing.setMinSalary(dto.getMinSalary());
        existing.setMaxSalary(dto.getMaxSalary());
        existing.setEmploymentType(dto.getEmploymentType());
        existing.setJobStatus(dto.getJobStatus());
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
        eventPublisher.publish("notify.job", evt);

        return result;
    }

    @Override
    public void deleteJobOffer(UUID id) {
        if (!jobOfferRepo.existsById(id)) throw new RuntimeException("JobOffer not found: " + id);
        jobOfferRepo.deleteById(id);
    }
}