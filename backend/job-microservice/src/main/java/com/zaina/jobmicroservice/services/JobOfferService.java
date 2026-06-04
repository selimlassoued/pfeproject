package com.zaina.jobmicroservice.services;

import com.zaina.jobmicroservice.dto.JobEmbeddingDto;
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
    JobOfferDto incrementHiredCount(UUID jobId);
    JobOfferDto closeJob(UUID jobId, String actorUserId, String reason);
    void deleteJobOffer(UUID id);

    /** Returns the cached semantic embedding for a job, or null if none yet. */
    JobEmbeddingDto getEmbedding(UUID jobId);

    /** Persists the embedding for a job — overwriting any previous value. */
    void saveEmbedding(UUID jobId, JobEmbeddingDto dto);

    /**
     * Returns the cached embeddings for every requirement of a job that has
     * one. Requirements without a cached vector are simply omitted; the caller
     * decides whether to compute and PUT them back.
     */
    List<com.zaina.jobmicroservice.dto.RequirementEmbeddingDto> getRequirementEmbeddings(UUID jobId);

    /** Persists the embedding for a single requirement under a job. */
    void saveRequirementEmbedding(UUID jobId,
                                  UUID requirementId,
                                  com.zaina.jobmicroservice.dto.RequirementEmbeddingDto dto);
}