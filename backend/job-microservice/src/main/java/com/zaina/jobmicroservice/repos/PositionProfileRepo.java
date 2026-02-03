package com.zaina.jobmicroservice.repos;

import com.zaina.jobmicroservice.domain.entities.PositionProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PositionProfileRepo extends JpaRepository<PositionProfile, UUID> {
}
