package com.zaina.jobmicroservice.repos;

import com.zaina.jobmicroservice.domain.entities.JobRequirement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface JobRequirementRepo extends JpaRepository<JobRequirement, UUID> {

    List<JobRequirement> findByJobOffer_Id(UUID jobOfferId);

    /**
     * Persist the cached embedding for a single requirement. Native SQL with
     * an explicit ::vector cast — same reason as JobOfferRepo.updateEmbedding.
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE job_requirement " +
                   "SET embedding = CAST(:vec AS vector), embedding_model = :model " +
                   "WHERE id = :id",
           nativeQuery = true)
    int updateEmbedding(@Param("id") UUID id,
                        @Param("vec") String vec,
                        @Param("model") String model);
}
