package com.zaina.interviewservice.clients;

import com.zaina.interviewservice.dto.ApplicationStatusUpdateRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;


@Component
@RequiredArgsConstructor
@Slf4j
public class ApplicationClient {

    private final RestTemplate restTemplate;
    private final HttpServletRequest httpServletRequest;

    public void updateStatus(UUID applicationId, ApplicationStatusUpdateRequest request) {
        String authHeader = httpServletRequest.getHeader("Authorization");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (authHeader != null) {
            headers.set("Authorization", authHeader);
        }

        // Status goes as @RequestParam, not body
        String url = "http://gateway:8888/api/applications/{id}/status?status={status}";

        restTemplate.exchange(
                url,
                HttpMethod.PATCH,
                new HttpEntity<>(null, headers),
                Void.class,
                applicationId,
                request.getStatus()
        );
    }
}