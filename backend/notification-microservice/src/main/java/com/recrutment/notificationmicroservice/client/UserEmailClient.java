package com.recrutment.notificationmicroservice.client;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserEmailClient {

    private final RestTemplate restTemplate;

    /**
     * The /api/admin/internal/users/{id}/email endpoint is hosted IN the
     * gateway service (AdminUsersController), not proxied. We address it
     * by Eureka service id so the call goes through the load balancer.
     */
    private static final String GATEWAY = "http://GATEWAYSERVER";

    public String getEmailByUserId(String userId) {
        Map<String, String> profile = getUserProfile(userId);
        return profile != null ? profile.get("email") : null;
    }

    /**
     * Returns map with email, firstName, lastName for greeting/sign-off in emails.
     */
    public Map<String, String> getUserProfile(String userId) {
        String url = GATEWAY + "/api/admin/internal/users/{id}/email";
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> resp = restTemplate.getForObject(url, Map.class, userId);
            return resp;
        } catch (Exception e) {
            return null;
        }
    }
}