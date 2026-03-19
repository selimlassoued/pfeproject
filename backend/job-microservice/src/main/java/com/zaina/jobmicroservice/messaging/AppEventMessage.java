package com.zaina.jobmicroservice.messaging;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@AllArgsConstructor
@NoArgsConstructor
@Data
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
    public static class Actor {
        private String userId;
        private List<String> roles;
    }
    @Data
    public static class Target {
        private String type;
        private String id;
    }
}