package com.recrutment.auditservice.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuditEventMessage {

    private UUID eventId;
    private String eventType;
    private Instant occurredAt;

    /**
     * Name of the producing service (gatewayserver, job-microservice, etc.).
     * Not required by the contract, but useful for debugging.
     */
    private String producer;

    private Actor actor;
    private Target target;

    private String reason;

    /**
     * Arbitrary diff structure (old/new or changed fields).
     */
    private Map<String, Object> changes;

    /**
     * Additional payload that publishers might attach.
     */
    private Map<String, Object> payload;

    private String correlationId;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Actor {
        private String userId;
        private List<String> roles;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Target {
        private String type;
        private String id;
    }
}

