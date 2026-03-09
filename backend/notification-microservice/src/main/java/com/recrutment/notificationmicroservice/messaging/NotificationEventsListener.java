package com.recrutment.notificationmicroservice.messaging;

import com.recrutment.notificationmicroservice.services.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class NotificationEventsListener {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = "${app.messaging.notification-queue}")
    public void handleNotificationEvent(String json) {
        AppEventMessage evt;
        try {
            evt = objectMapper.readValue(json, AppEventMessage.class);
        } catch (Exception e) {
            // Invalid JSON - log and discard (don't re-queue)
            org.slf4j.LoggerFactory.getLogger(NotificationEventsListener.class)
                    .warn("Invalid JSON in notification event, discarding: {}", e.getMessage());
            return;
        }

        switch (evt.getEventType()) {
            case "USER_BLOCK" -> notificationService.handleUserBlock(evt);
            case "USER_UNBLOCK" -> notificationService.handleUserUnblock(evt);
            case "ROLE_UPDATE" -> notificationService.handleRoleUpdate(evt);
            case "APPLICATION_STATUS_UPDATE" -> notificationService.handleApplicationStatusUpdate(evt);
            case "JOB_UPDATED" -> notificationService.handleJobUpdated(evt);
            default -> { /* ignore */ }
        }
    }
}