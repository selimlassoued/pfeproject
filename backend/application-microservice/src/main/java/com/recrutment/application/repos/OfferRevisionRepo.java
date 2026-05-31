package com.recrutment.application.repos;

import com.recrutment.application.entities.OfferRevision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OfferRevisionRepo extends JpaRepository<OfferRevision, UUID> {
    List<OfferRevision> findByOfferIdOrderByCreatedAtAsc(UUID offerId);
}
