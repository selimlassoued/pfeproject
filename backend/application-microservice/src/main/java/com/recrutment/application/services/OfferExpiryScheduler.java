package com.recrutment.application.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Runs every minute and flips overdue SENT/NEGOTIATING offers to EXPIRED. */
@Component
@RequiredArgsConstructor
@Slf4j
public class OfferExpiryScheduler {

    private final OfferService offerService;

    @Scheduled(fixedDelay = 60_000)
    public void tick() {
        try {
            int expired = offerService.expirePending(Instant.now());
            if (expired > 0) log.info("Offer expiry sweep: expired {}", expired);
        } catch (Exception e) {
            log.error("Offer expiry sweep failed: {}", e.getMessage(), e);
        }
    }
}
