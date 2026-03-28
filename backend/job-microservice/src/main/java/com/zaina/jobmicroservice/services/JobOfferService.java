package com.zaina.jobmicroservice.services;

import com.zaina.jobmicroservice.dto.JobOfferDto;
import com.zaina.jobmicroservice.dto.PageResponse;
import com.zaina.jobmicroservice.domain.enums.EmploymentType;
import com.zaina.jobmicroservice.domain.enums.JobStatus;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface JobOfferService {
    JobOfferDto getJobOfferById(UUID id);

    List<JobOfferDto> getJobOffers();

    PageResponse<JobOfferDto> searchJobs(String query, EmploymentType employmentType, JobStatus jobStatus, Integer minSalary, Integer maxSalary, Pageable pageable);

    JobOfferDto createJobOffer(JobOfferDto dto, String actorUserId);
    JobOfferDto updateJobOffer(UUID id, JobOfferDto dto, String reason, String actorUserId);
    void deleteJobOffer(UUID id);
}