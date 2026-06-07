package com.zaina.interviewservice.clients;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

// The /api/admin/users endpoint is hosted in the gateway service
// (AdminUsersController), so we point Feign at the gateway by its
// Eureka service id. Dropping the explicit `url=` lets Feign resolve
// through Spring Cloud LoadBalancer.
@FeignClient(name = "gatewayserver", contextId = "userClient")
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