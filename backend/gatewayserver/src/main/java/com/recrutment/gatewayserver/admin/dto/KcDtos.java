package com.recrutment.gatewayserver.admin.dto;

import java.util.List;
import java.util.Map;

public class KcDtos {

    public record TokenResponse(String access_token, long expires_in) {}

    public record KcUser(
            String id,
            String username,
            String firstName,
            String lastName,
            String email,
            Boolean enabled,
            Long createdTimestamp,
            Map<String, List<String>> attributes,
            List<String> roles // ✅ added
    ) {}

    public record KcRole(String id, String name, String description) {}
    public record KcUserUpdate(Boolean enabled) {}
}
