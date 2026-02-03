package com.zaina.jobmicroservice.services;

import com.zaina.jobmicroservice.domain.entities.JobOffer;
import com.zaina.jobmicroservice.domain.entities.JobRequirement;
import com.zaina.jobmicroservice.dto.JobOfferDto;
import com.zaina.jobmicroservice.dto.JobRequirementDto;
import com.zaina.jobmicroservice.repos.JobOfferRepo;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@AllArgsConstructor
@Transactional
public class JobOfferServiceImpl implements JobOfferService {

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
    public JobOfferDto createJobOffer(JobOfferDto dto) {
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

        return toDto(jobOfferRepo.save(entity));
    }

    @Override
    public JobOfferDto updateJobOffer(UUID id, JobOfferDto dto) {
        JobOffer existing = jobOfferRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("JobOffer not found: " + id));

        existing.setTitle(dto.getTitle());
        existing.setDescription(dto.getDescription());
        existing.setLocation(dto.getLocation());
        existing.setMinSalary(dto.getMinSalary());
        existing.setMaxSalary(dto.getMaxSalary());
        existing.setEmploymentType(dto.getEmploymentType());
        existing.setJobStatus(dto.getJobStatus());

        existing.getRequirements().clear(); // orphanRemoval=true deletes old ones
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

        return toDto(jobOfferRepo.save(existing));
    }

    @Override
    public void deleteJobOffer(UUID id) {
        if (!jobOfferRepo.existsById(id)) {
            throw new RuntimeException("JobOffer not found: " + id);
        }
        jobOfferRepo.deleteById(id);
    }
}
