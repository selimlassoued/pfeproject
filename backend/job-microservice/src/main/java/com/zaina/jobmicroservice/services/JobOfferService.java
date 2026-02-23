package com.zaina.jobmicroservice.services;

import com.zaina.jobmicroservice.dto.JobOfferDto;

import java.util.List;
import java.util.UUID;

public interface JobOfferService {
    JobOfferDto getJobOfferById(UUID id);
    List<JobOfferDto> getJobOffers();
    JobOfferDto createJobOffer(JobOfferDto dto, String actorUserId);
    JobOfferDto updateJobOffer(UUID id, JobOfferDto dto, String reason, String actorUserId);
    void deleteJobOffer(UUID id);
}
