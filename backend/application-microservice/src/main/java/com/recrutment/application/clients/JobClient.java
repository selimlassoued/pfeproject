package com.recrutment.application.clients;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

@Slf4j
@Component
public class JobClient {

    private final RestTemplate restTemplate;

    public JobClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public JobDto getJob(UUID id) {
        String url = "http://gateway:8888/api/jobs/" + id;

        try {
            log.info("[JobClient] GET {}", url);
            ResponseEntity<JobDto> resp = restTemplate.getForEntity(url, JobDto.class);
            log.info("[JobClient] Status={} body={}", resp.getStatusCode(), resp.getBody());
            return resp.getBody();

        } catch (HttpStatusCodeException e) {
            log.error("[JobClient] HTTP error calling {} -> status={} body={}",
                    url, e.getStatusCode(), e.getResponseBodyAsString(), e);
            return null;

        } catch (ResourceAccessException e) {
            log.error("[JobClient] Network error calling {} -> {}",
                    url, e.getMessage(), e);
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class JobDto {

        // accept different field names coming from job service
        @JsonAlias({"id", "jobId", "jobOfferId"})
        private UUID id;

        @JsonAlias({"title", "jobTitle", "name"})
        private String title;
    }
}
