package com.zaina.interviewservice.clients;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.UUID;

// Service id matches job-microservice's spring.application.name. Dropping
// the explicit `url=` makes Feign resolve the call through Spring Cloud
// LoadBalancer instead of a literal hostname.
@FeignClient(name = "job-microservice")
public interface JobClient {

    @GetMapping("/api/jobs/{jobId}")
    JobSummary getJob(@PathVariable("jobId") UUID jobId);

    record JobSummary(
            String title,
            String description,
            List<RequirementSummary> requirements
    ) {}

    record RequirementSummary(
            String category,
            String description
    ) {}
}
