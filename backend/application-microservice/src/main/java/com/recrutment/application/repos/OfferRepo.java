package com.recrutment.application.repos;

import com.recrutment.application.entities.Offer;
import com.recrutment.application.enums.OfferStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OfferRepo extends JpaRepository<Offer, UUID> {
    Optional<Offer> findByApplicationId(UUID applicationId);
    List<Offer> findByCandidateUserId(String candidateUserId);
    List<Offer> findByRecruiterId(UUID recruiterId);
    List<Offer> findByStatusIn(List<OfferStatus> statuses);
}
