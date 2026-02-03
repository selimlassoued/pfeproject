package com.zaina.jobmicroservice.repos;

import com.zaina.jobmicroservice.domain.entities.JobOffer;
import com.zaina.jobmicroservice.domain.entities.JobRequirement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JobRequirementRepo extends JpaRepository<JobRequirement, UUID> {
    List<JobRequirement> findByJobOffer_Id(UUID jobOfferId);
}
