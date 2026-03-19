package com.recrutment.notificationmicroservice.client;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserEmailClient {

    private final RestTemplate restTemplate;

    @Value("${app.internal.gateway-base-url:http://gateway:8888}")
    private String gatewayBaseUrl;

    public String getEmailByUserId(String userId) {
        Map<String, String> profile = getUserProfile(userId);
        return profile != null ? profile.get("email") : null;
    }

    /**
     * Returns map with email, firstName, lastName for greeting/sign-off in emails.
     */
    public Map<String, String> getUserProfile(String userId) {
        String url = gatewayBaseUrl + "/api/admin/internal/users/{id}/email";
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> resp = restTemplate.getForObject(url, Map.class, userId);
            return resp;
        } catch (Exception e) {
            return null;
        }
    }
}