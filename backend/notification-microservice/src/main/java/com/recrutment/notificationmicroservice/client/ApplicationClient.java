package com.recrutment.notificationmicroservice.client;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ApplicationClient {

    private final RestTemplate restTemplate;

    /** Eureka service id, resolved by the @LoadBalanced RestTemplate. */
    private static final String APPLICATION_SERVICE = "http://APPLICATION-MICROSERVICE";

    public List<String> findCandidateUserIdsByJob(UUID jobId) {
        String url = APPLICATION_SERVICE + "/api/applications/internal/job/{jobId}/candidate-ids";
        try {
            List<String> ids = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<List<String>>() {},
                    jobId
            ).getBody();
            return ids != null ? ids : Collections.emptyList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
