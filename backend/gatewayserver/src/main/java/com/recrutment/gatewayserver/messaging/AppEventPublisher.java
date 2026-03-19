package com.recrutment.gatewayserver.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AppEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.messaging.exchange}")
    private String exchangeName;

    public void publish(String routingKey, AppEventMessage event) {
        try {
            if (event.getEventId() == null) {
                event.setEventId(java.util.UUID.randomUUID());
            }
            if (event.getOccurredAt() == null) {
                event.setOccurredAt(java.time.Instant.now());
            }
            System.out.println("SENDING TO EXCHANGE = " + exchangeName + " WITH KEY = " + routingKey);
            String json = objectMapper.writeValueAsString(event);
            rabbitTemplate.convertAndSend(exchangeName, routingKey, json);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event {}", event, e);
        }
    }
}