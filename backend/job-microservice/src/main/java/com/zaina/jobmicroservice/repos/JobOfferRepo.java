package com.zaina.jobmicroservice.repos;

import com.zaina.jobmicroservice.domain.entities.JobOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface JobOfferRepo extends JpaRepository<JobOffer, UUID> {
}
