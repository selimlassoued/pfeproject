package com.recrutment.notificationmicroservice.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;


@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppEventMessage {

    private UUID eventId;
    private String eventType;
    private Instant occurredAt;
    private Actor actor;
    private Target target;
    private String reason;
    private Map<String, Object> changes;
    private Map<String, Object> payload;
    private String correlationId;
    private String producer;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Target {
        private String type;
        private String id;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Actor {
        private String userId;
        private List<String> roles;
    }

}