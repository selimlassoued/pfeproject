package com.zaina.interviewservice.clients;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

@FeignClient(name = "user-client",
        url = "${user.service.url:http://gateway:8888}")
public interface UserClient {

    @GetMapping("/api/admin/users/{userId}")
    UserProfile getUserProfile(@PathVariable("userId") UUID userId);

    record UserProfile(
            UUID id,
            String firstName,
            String lastName,
            String email
    ) {
        public String fullName() {
            if (firstName == null && lastName == null) return "";
            return ((firstName != null ? firstName : "")
                    + " "
                    + (lastName != null ? lastName : "")).trim();
        }
    }
}