package com.zaina.jobmicroservice.restControllers;

import com.zaina.jobmicroservice.dto.JobOfferDto;
import com.zaina.jobmicroservice.dto.PageResponse;
import com.zaina.jobmicroservice.domain.enums.EmploymentType;
import com.zaina.jobmicroservice.domain.enums.JobStatus;
import com.zaina.jobmicroservice.services.JobOfferService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobOfferController {

    private final JobOfferService service;

    /**
     * Get all jobs (for backward compatibility)
     * GET /api/jobs
     */
    @GetMapping
    public List<JobOfferDto> getAll() {
        return service.getJobOffers();
    }

    /**
     *
     * @param query Search term for title, location, description
     * @param employmentType Filter by employment type as STRING (FULL_TIME, PART_TIME, CONTRACT, INTERNSHIP)
     * @param jobStatus Filter by job status as STRING (DRAFT, PUBLISHED, CLOSED)
     * @param minSalary Filter by minimum salary
     * @param maxSalary Filter by maximum salary
     * @param page Page number (0-indexed, default 0)
     * @param size Items per page (default 10)
     * @return Paginated response with filtered jobs
     */
    @GetMapping("/search")
    public PageResponse<JobOfferDto> search(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String employmentType,  // STRING not enum!
            @RequestParam(required = false) String jobStatus,       // STRING not enum!
            @RequestParam(required = false) Integer minSalary,
            @RequestParam(required = false) Integer maxSalary,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        // Defensive pagination: keep API stable even with bad inputs
        if (page < 0) page = 0;
        if (size < 1) size = 10;
        if (size > 100) size = 100;

        // Defensive salary range: if swapped, normalize
        if (minSalary != null && maxSalary != null && minSalary > maxSalary) {
            int tmp = minSalary;
            minSalary = maxSalary;
            maxSalary = tmp;
        }

        // FIX #2: Convert string parameters to enums with graceful error handling
        EmploymentType employment = null;
        if (employmentType != null && !employmentType.isBlank()) {
            try {
                employment = EmploymentType.valueOf(employmentType.toUpperCase());
            } catch (IllegalArgumentException e) {
                // Invalid enum value - silently ignore, treat as no filter
                // This prevents 400 errors when invalid enum values are passed
            }
        }

        JobStatus status = null;
        if (jobStatus != null && !jobStatus.isBlank()) {
            try {
                status = JobStatus.valueOf(jobStatus.toUpperCase());
            } catch (IllegalArgumentException e) {
                // Invalid enum value - silently ignore, treat as no filter
                // This prevents 400 errors when invalid enum values are passed
            }
        }

        Pageable pageable = PageRequest.of(page, size);
        return service.searchJobs(query, employment, status, minSalary, maxSalary, pageable);
    }

    /**
     * Get a specific job by ID
     * GET /api/jobs/{id}
     *
     * ⚠️ IMPORTANT: This MUST come AFTER /search endpoint
     * FIX #1: Otherwise /search will be matched as an ID!
     *
     * @param id The job ID (UUID)
     * @return The job offer
     */
    @GetMapping("/{id}")
    public JobOfferDto getById(@PathVariable UUID id) {
        return service.getJobOfferById(id);
    }

    /**
     * Create a new job offer
     * POST /api/jobs
     */
    public static final String ACTOR_USER_ID_HEADER = "X-Actor-User-Id";

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JobOfferDto create(
            @RequestBody JobOfferDto dto,
            @RequestHeader(name = ACTOR_USER_ID_HEADER, required = false) String actorUserId) {
        return service.createJobOffer(dto, actorUserId);
    }

    /**
     * Update an existing job offer
     * PUT /api/jobs/{id}
     */
    @PutMapping("/{id}")
    public JobOfferDto update(
            @PathVariable UUID id,
            @RequestBody JobOfferDto dto,
            @RequestParam(required = false) String reason,
            @RequestHeader(name = ACTOR_USER_ID_HEADER, required = false) String actorUserId) {
        return service.updateJobOffer(id, dto, reason, actorUserId);
    }

    /**
     * Delete a job offer
     * DELETE /api/jobs/{id}
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.deleteJobOffer(id);
    }
}