package com.recrutment.auditservice.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recrutment.auditservice.entities.AuditLog;
import com.recrutment.auditservice.repos.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuditEventsListener {

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = "${app.messaging.audit-queue}")
    public void handleAuditEvent(String messageJson) {
        try {
            log.debug("Received audit event message: {}", messageJson);
            AuditEventMessage msg = objectMapper.readValue(messageJson, AuditEventMessage.class);
            log.debug("Deserialized eventType={}, producer={}, changes={}", msg.getEventType(), msg.getProducer(), msg.getChanges());

            if (msg.getEventId() == null) {
                msg.setEventId(UUID.randomUUID());
            }
            if (msg.getOccurredAt() == null) {
                msg.setOccurredAt(Instant.now());
            }

            String actorUserId = "SYSTEM";
            String actorRolesSerialized = null;
            if (msg.getActor() != null) {
                if (msg.getActor().getUserId() != null && !msg.getActor().getUserId().isBlank()) {
                    actorUserId = msg.getActor().getUserId();
                }
                List<String> roles = msg.getActor().getRoles();
                if (roles != null && !roles.isEmpty()) {
                    actorRolesSerialized = objectMapper.writeValueAsString(roles);
                }
            }

            String targetType = msg.getTarget() != null ? msg.getTarget().getType() : null;
            String targetId = msg.getTarget() != null ? msg.getTarget().getId() : null;

            String changesSerialized = null;
            if (msg.getChanges() != null && !msg.getChanges().isEmpty()) {
                log.debug("msg.getChanges() = {} (class={})", msg.getChanges(), msg.getChanges().getClass().getName());
                changesSerialized = objectMapper.writeValueAsString(msg.getChanges());
                log.debug("changesSerialized = {}", changesSerialized);
            } else {
                log.warn("msg.getChanges() is null or empty for eventType={}, targetId={}", msg.getEventType(), targetId);
            }

            AuditLog logEntity = AuditLog.builder()
                    .eventId(msg.getEventId())
                    .eventType(msg.getEventType())
                    .occurredAt(msg.getOccurredAt())
                    .producer(msg.getProducer())
                    .actorUserId(actorUserId)
                    .actorRoles(actorRolesSerialized)
                    .targetType(targetType)
                    .targetId(targetId)
                    .reason(msg.getReason())
                    .changes(changesSerialized)
                    .correlationId(msg.getCorrelationId())
                    .createdAt(Instant.now())
                    .build();

            repository.save(logEntity);
        } catch (Exception e) {
            log.error("Failed to process audit event message: {}", messageJson, e);
        }
    }
}

