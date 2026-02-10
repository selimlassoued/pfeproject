package com.recrutment.application.clients;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class UserClient {

    private final RestTemplate restTemplate;

    public UserClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public KcUser getUser(String id) {
        String url = "http://gateway:8888/api/admin/users/" + id;

        try {
            log.info("[UserClient] GET {}", url);
            ResponseEntity<KcUser> resp = restTemplate.getForEntity(url, KcUser.class);
            log.info("[UserClient] Status={} body={}", resp.getStatusCode(), resp.getBody());
            return resp.getBody();

        } catch (HttpStatusCodeException e) {
            // This will show you if it's 401/403
            log.error("[UserClient] HTTP error calling {} -> status={} body={}",
                    url, e.getStatusCode(), e.getResponseBodyAsString(), e);
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class KcUser {
        private String id;
        private String username;
        private String firstName;
        private String lastName;
        private String email;

        public String getFullName() {
            String fn = firstName == null ? "" : firstName.trim();
            String ln = lastName == null ? "" : lastName.trim();
            return (fn + " " + ln).trim();
        }
    }
}
