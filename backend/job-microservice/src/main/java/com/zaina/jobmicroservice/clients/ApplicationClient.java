package com.zaina.jobmicroservice.clients;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ApplicationClient {

    private final RestTemplate restTemplate;

    public void rejectNonHiredForJob(UUID jobId) {
        String url = "http://APPLICATION-MICROSERVICE/api/applications/internal/job/" + jobId + "/reject-non-hired";
        try {
            log.info("[ApplicationClient] POST {}", url);
            ResponseEntity<Void> resp = restTemplate.postForEntity(url, null, Void.class);
            log.info("[ApplicationClient] Status={}", resp.getStatusCode());
        } catch (Exception e) {
            log.error("[ApplicationClient] Failed calling {}: {}", url, e.getMessage());
        }
    }

    public boolean hasApplications(UUID jobId) {
        String url = "http://APPLICATION-MICROSERVICE/api/applications/internal/job/" + jobId + "/candidate-ids";
        try {
            ResponseEntity<java.util.List> resp = restTemplate.getForEntity(url, java.util.List.class);
            return resp.getBody() != null && !resp.getBody().isEmpty();
        } catch (Exception e) {
            log.warn("[ApplicationClient] hasApplications check failed for job {}: {}", jobId, e.getMessage());
            return false;
        }
    }

    public void deleteApplicationsForJob(UUID jobId) {
        String url = "http://APPLICATION-MICROSERVICE/api/applications/internal/job/" + jobId;
        try {
            log.info("[ApplicationClient] DELETE {}", url);
            restTemplate.delete(url);
            log.info("[ApplicationClient] Applications deleted for job {}", jobId);
        } catch (Exception e) {
            log.error("[ApplicationClient] Failed deleting applications for job {}: {}", jobId, e.getMessage());
        }
    }
}

