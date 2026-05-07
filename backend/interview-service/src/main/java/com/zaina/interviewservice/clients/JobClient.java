package com.zaina.interviewservice.clients;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.UUID;

@FeignClient(name = "job-client",
        url = "${job.service.url:http://job-service:8082}")
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
