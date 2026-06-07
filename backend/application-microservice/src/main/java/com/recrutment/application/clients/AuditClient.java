package com.recrutment.application.clients;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
@Slf4j
public class AuditClient {

    private final RestTemplate restTemplate;

    /** Eureka service id, resolved by the @LoadBalanced RestTemplate. */
    private static final String AUDIT_SERVICE = "http://AUDIT-SERVICE";

    public AuditClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Returns the userId who last flagged this candidate.
     * Calls GET /api/audit/candidate/{candidateUserId}/flagged-by
     * Returns null if audit service unavailable or no signal found.
     */
    @SuppressWarnings("unchecked")
    public String getLastFlaggedBy(String candidateUserId) {
        try {
            String url = AUDIT_SERVICE + "/api/audit/candidate/" + candidateUserId + "/flagged-by";
            Map<String, String> response = restTemplate.getForObject(url, Map.class);
            if (response != null) {
                String flaggedBy = response.get("flaggedBy");
                return (flaggedBy != null && !flaggedBy.isBlank()) ? flaggedBy : null;
            }
        } catch (Exception e) {
            log.warn("[AuditClient] Could not retrieve flaggedBy for {}: {}", candidateUserId, e.getMessage());
        }
        return null;
    }
}