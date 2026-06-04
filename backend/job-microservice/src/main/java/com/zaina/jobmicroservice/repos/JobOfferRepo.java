package com.zaina.jobmicroservice.repos;

import com.zaina.jobmicroservice.domain.entities.JobOffer;
import com.zaina.jobmicroservice.domain.enums.EmploymentType;
import com.zaina.jobmicroservice.domain.enums.JobStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

public interface JobOfferRepo extends JpaRepository<JobOffer, UUID> {

    /**
     * Search and filter jobs with pagination
     * Searches in title, location, and description
     *
     * @param query Search query (searches title, location, description)
     * @param employmentType Filter by employment type
     * @param jobStatus Filter by job status
     * @param minSalary Filter by minimum salary
     * @param maxSalary Filter by maximum salary
     * @param pageable Pagination parameters
     * @return Page of JobOffers matching the criteria
     */
    @Query("""
        SELECT j FROM JobOffer j
        WHERE (
            :query IS NULL OR :query = '' OR
            LOWER(COALESCE(j.title, '')) LIKE CONCAT('%', LOWER(:query), '%') OR
            LOWER(COALESCE(j.location, '')) LIKE CONCAT('%', LOWER(:query), '%') OR
            LOWER(COALESCE(j.description, '')) LIKE CONCAT('%', LOWER(:query), '%')
        )
        AND (:employmentType IS NULL OR j.employmentType = :employmentType)
        AND (:jobStatus IS NULL OR j.jobStatus = :jobStatus)
        AND (:minSalary IS NULL OR j.minSalary >= :minSalary)
        AND (:maxSalary IS NULL OR j.maxSalary <= :maxSalary)
        ORDER BY j.title ASC, j.id ASC
        """)
    Page<JobOffer> searchAndFilter(
            @Param("query") String query,
            @Param("employmentType") EmploymentType employmentType,
            @Param("jobStatus") JobStatus jobStatus,
            @Param("minSalary") Integer minSalary,
            @Param("maxSalary") Integer maxSalary,
            Pageable pageable
    );

    @Query("SELECT COALESCE(MAX(CAST(SUBSTRING(j.refNumber, 5) AS int)), 0) + 1 " +
            "FROM JobOffer j WHERE j.refNumber IS NOT NULL")
    int nextRefSequence();

    /**
     * Persist the cached embedding for a job. Native SQL with an explicit
     * ::vector cast — JPA's generated UPDATE can't bind a String to a
     * vector(768) column directly. Called from JobOfferServiceImpl.saveEmbedding.
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE job_offer " +
                   "SET embedding = CAST(:vec AS vector), embedding_model = :model " +
                   "WHERE id = :id",
           nativeQuery = true)
    int updateEmbedding(@Param("id") UUID id,
                        @Param("vec") String vec,
                        @Param("model") String model);
}