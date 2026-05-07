package com.recrutment.application.clients;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
@Slf4j
public class AuditClient {

    private final RestTemplate restTemplate;

    @Value("${app.audit.url:http://audit-service:8080}")
    private String auditUrl;

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
            String url = auditUrl + "/api/audit/candidate/" + candidateUserId + "/flagged-by";
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