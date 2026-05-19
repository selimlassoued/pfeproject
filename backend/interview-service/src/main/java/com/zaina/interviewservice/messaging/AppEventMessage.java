package com.zaina.interviewservice.messaging;

import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Cross-service event envelope — mirrors the shape every other microservice
 * publishes onto the {@code app.events} topic exchange. The notification
 * service deserialises this (it ignores unknown fields), so only the bits we
 * actually populate need to be here.
 */
@Data
public class AppEventMessage {
    private UUID eventId;
    private String eventType;
    private Instant occurredAt;
    private String producer;
    private Map<String, Object> payload;
}
