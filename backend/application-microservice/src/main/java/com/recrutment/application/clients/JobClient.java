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
import java.util.List;

@Slf4j
@Component
public class JobClient {

    private final RestTemplate restTemplate;

    public JobClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @org.springframework.beans.factory.annotation.Value("${job.service.internal.url:http://job-microservice:8080}")
    private String jobServiceInternalUrl;

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

    public void incrementHired(UUID jobId) {
        // Internal call — bypass gateway to avoid auth requirement
        String url = jobServiceInternalUrl + "/api/jobs/" + jobId + "/hired";
        try {
            log.info("[JobClient] POST {}", url);
            restTemplate.postForEntity(url, null, Void.class);
        } catch (Exception e) {
            log.error("[JobClient] Failed increment hired for {}: {}", jobId, e.getMessage());
        }
    }

    public JobDto closeJob(UUID jobId) {
        // Internal call — bypass gateway to avoid auth requirement
        String url = jobServiceInternalUrl + "/api/jobs/" + jobId + "/close";
        try {
            log.info("[JobClient] POST {}", url);
            ResponseEntity<JobDto> resp = restTemplate.postForEntity(url, null, JobDto.class);
            return resp.getBody();
        } catch (Exception e) {
            log.error("[JobClient] Failed to close job {}: {}", jobId, e.getMessage());
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

        @JsonAlias({"workArrangement"})
        private String workArrangement;

        @JsonAlias({"description", "jobDescription"})
        private String description;

        @JsonAlias({"requirements"})
        private List<JobRequirementDto> requirements;

        @JsonAlias({"jobStatus"})
        private String jobStatus;

        @JsonAlias({"openings"})
        private Integer openings;

        @JsonAlias({"hiredCount"})
        private Integer hiredCount;

        @JsonAlias({"skillsWeight"})
        private Double skillsWeight;

        @JsonAlias({"semanticWeight"})
        private Double semanticWeight;

        @JsonAlias({"experienceWeight"})
        private Double experienceWeight;

        @JsonAlias({"seniorityWeight"})
        private Double seniorityWeight;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class JobRequirementDto {
        private String category;
        private String description;
        private Double weight;

        @JsonAlias({"minYears", "min_years"})
        private Integer minYears;

        @JsonAlias({"maxYears", "max_years"})
        private Integer maxYears;

        @JsonAlias({"skillLevel", "skill_level"})
        private String skillLevel;

        @JsonAlias({"degreeLevel", "degree_level"})
        private String degreeLevel;

        @JsonAlias({"enrollmentType", "enrollment_type"})
        private String enrollmentType;

        @JsonAlias({"languageLevel", "language_level"})
        private String languageLevel;
    }
}
