package com.zaina.interviewservice.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Publishes interview events onto the shared {@code app.events} topic exchange
 * with a {@code notify.*} routing key, so the notification service picks them up.
 *
 * <p>Sends a plain text/plain JSON body via {@link RabbitTemplate#send} — this
 * bypasses the service's Jackson message converter (used by the analysis flow)
 * and matches exactly what the other producers put on the wire.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InterviewEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.messaging.exchange:app.events}")
    private String exchangeName;

    public void publish(String routingKey, AppEventMessage event) {
        try {
            if (event.getEventId() == null)   event.setEventId(UUID.randomUUID());
            if (event.getOccurredAt() == null) event.setOccurredAt(Instant.now());

            String json = objectMapper.writeValueAsString(event);
            Message message = MessageBuilder
                    .withBody(json.getBytes(StandardCharsets.UTF_8))
                    .setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN)
                    .build();
            rabbitTemplate.send(exchangeName, routingKey, message);
            log.info("Published {} event to {}/{}", event.getEventType(), exchangeName, routingKey);
        } catch (Exception e) {
            log.warn("Failed to publish interview event {}: {}",
                    event.getEventType(), e.getMessage());
        }
    }
}
