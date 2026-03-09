package com.zaina.jobmicroservice.repos;

import com.zaina.jobmicroservice.domain.entities.JobOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface JobOfferRepo extends JpaRepository<JobOffer, UUID> {

    @Query("SELECT COALESCE(MAX(CAST(SUBSTRING(j.refNumber, 5) AS int)), 0) + 1 " +
            "FROM JobOffer j WHERE j.refNumber IS NOT NULL")
    int nextRefSequence();
}