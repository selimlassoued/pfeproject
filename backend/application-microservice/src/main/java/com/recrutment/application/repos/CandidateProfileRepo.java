package com.recrutment.application.repos;

import com.recrutment.application.entities.CandidateProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CandidateProfileRepo extends JpaRepository<CandidateProfile, String> {
    Optional<CandidateProfile> findByUserId(String userId);
}
