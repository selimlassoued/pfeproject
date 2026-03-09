package com.recrutment.notificationmicroservice.services;


import com.recrutment.notificationmicroservice.client.ApplicationClient;
import com.recrutment.notificationmicroservice.client.UserEmailClient;
import com.recrutment.notificationmicroservice.entity.Notification;
import com.recrutment.notificationmicroservice.entity.NotificationType;
import com.recrutment.notificationmicroservice.repos.NotificationRepo;
import com.recrutment.notificationmicroservice.messaging.AppEventMessage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final UserEmailClient userEmailClient;

    private final NotificationRepo repo;
    private final JavaMailSender mailSender;
    private final ApplicationClient applicationClient;

    public void handleUserBlock(AppEventMessage evt) {
        String userId = evt.getTarget().getId();
        String reason = evt.getReason();

        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(NotificationType.USER_BLOCK);
        n.setTitle("Your account has been blocked");
        n.setBody(reason != null ? reason : "Your account was blocked by an administrator.");
        repo.save(n);

        // Push to a per-user topic so it works without WS authentication (dev-friendly)
        messagingTemplate.convertAndSend("/topic/notifications." + n.getUserId(), n);
        // Keep user-destination send for later when WS auth is enabled
        messagingTemplate.convertAndSendToUser(n.getUserId(), "/queue/notifications", n);

        sendEmailToUser(userId, n.getTitle(), n.getBody());
    }

    public void handleUserUnblock(AppEventMessage evt) {
        String userId = evt.getTarget().getId();
        String reason = evt.getReason();

        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(NotificationType.USER_UNBLOCK);
        n.setTitle("Your account has been unblocked");
        n.setBody(reason != null ? reason : "Your account was unblocked by an administrator.");
        repo.save(n);

        messagingTemplate.convertAndSend("/topic/notifications." + n.getUserId(), n);
        messagingTemplate.convertAndSendToUser(n.getUserId(), "/queue/notifications", n);

        sendEmailToUser(userId, n.getTitle(), n.getBody());
    }

    public void handleRoleUpdate(AppEventMessage evt) {
        String userId = evt.getTarget().getId();
        Map<String, Object> changes = evt.getChanges();
        List<String> oldRoles = (List<String>) changes.get("oldRoles");
        List<String> newRoles = (List<String>) changes.get("newRoles");

        String body = "Your roles have changed.\nOld: " + oldRoles + "\nNew: " + newRoles;

        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(NotificationType.ROLE_UPDATE);
        n.setTitle("Your roles were updated");
        n.setBody(body);
        repo.save(n);

        messagingTemplate.convertAndSend("/topic/notifications." + n.getUserId(), n);
        messagingTemplate.convertAndSendToUser(n.getUserId(), "/queue/notifications", n);

        sendEmailToUser(userId, n.getTitle(), n.getBody());
    }

    public void handleApplicationStatusUpdate(AppEventMessage evt) {
        Map<String, Object> payload = evt.getPayload();
        String candidateUserId = (String) payload.get("candidateUserId");
        String oldStatus = (String) payload.get("oldStatus");
        String newStatus = (String) payload.get("newStatus");

        String body = "Your application status changed from " + oldStatus + " to " + newStatus + ".";

        Notification n = new Notification();
        n.setUserId(candidateUserId);
        n.setType(NotificationType.APPLICATION_STATUS_UPDATE);
        n.setTitle("Application status updated");
        n.setBody(body);
        repo.save(n);

        messagingTemplate.convertAndSend("/topic/notifications." + n.getUserId(), n);
        messagingTemplate.convertAndSendToUser(n.getUserId(), "/queue/notifications", n);

        sendEmailToUser(candidateUserId, n.getTitle(), n.getBody());
    }

    public void handleJobUpdated(AppEventMessage evt) {
        UUID jobId = UUID.fromString(evt.getTarget().getId());
        String jobTitle = resolveJobTitle(evt);
        String body = buildJobUpdatedBody(jobTitle, evt.getChanges());

        List<String> candidateIds = applicationClient.findCandidateUserIdsByJob(jobId);

        for (String candidateUserId : candidateIds) {
            Notification n = new Notification();
            n.setUserId(candidateUserId);
            n.setType(NotificationType.JOB_UPDATED);
            n.setTitle("Job you applied to was updated");
            n.setBody(body);
            repo.save(n);

            messagingTemplate.convertAndSend("/topic/notifications." + n.getUserId(), n);
            messagingTemplate.convertAndSendToUser(n.getUserId(), "/queue/notifications", n);

            sendEmailToUser(candidateUserId, n.getTitle(), n.getBody());
        }
    }

    private String resolveJobTitle(AppEventMessage evt) {
        Map<String, Object> payload = evt.getPayload();
        if (payload != null && payload.containsKey("jobTitle")) {
            return String.valueOf(payload.get("jobTitle"));
        }
        Map<String, Object> changes = evt.getChanges();
        if (changes != null && changes.containsKey("title")) {
            Object titleChange = changes.get("title");
            if (titleChange instanceof Map<?, ?> m && m.containsKey("new")) {
                return String.valueOf(m.get("new"));
            }
        }
        return "the job";
    }

    private String buildJobUpdatedBody(String jobTitle, Map<String, Object> changes) {
        StringBuilder sb = new StringBuilder();
        sb.append("The job \"").append(jobTitle).append("\" you applied to has been updated.");
        if (changes != null && !changes.isEmpty()) {
            String fieldNames = changes.keySet().stream()
                    .map(this::formatFieldName)
                    .collect(Collectors.joining(", "));
            sb.append(" The following fields were changed: ").append(fieldNames).append(".\n");
        }
        sb.append("Please check the app to review the new details.");
        return sb.toString();
    }

    private String formatFieldName(String key) {
        return switch (key) {
            case "title" -> "Title";
            case "description" -> "Description";
            case "location" -> "Location";
            case "minSalary" -> "Min Salary";
            case "maxSalary" -> "Max Salary";
            case "employmentType" -> "Employment Type";
            case "jobStatus" -> "Job Status";
            default -> key.substring(0, 1).toUpperCase() + key.substring(1);
        };
    }

    private void sendEmailToUser(String userId, String subject, String body) {
        Map<String, String> profile = userEmailClient.getUserProfile(userId);
        if (profile == null) return;
        String email = profile.get("email");
        if (email == null || email.isBlank()) return;

        String fullBody = buildEmailBody(profile, body);

        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setTo(email);
        msg.setSubject(subject);
        msg.setText(fullBody);
        try {
            mailSender.send(msg);
        } catch (Exception e) {
            // In dev, avoid re-queuing RabbitMQ messages when email fails
            log.warn("Failed to send notification email to {}: {}", email, e.getMessage());
        }
    }

    private String buildEmailBody(Map<String, String> profile, String body) {
        String firstName = profile.getOrDefault("firstName", "").trim();
        String lastName = profile.getOrDefault("lastName", "").trim();
        String name = (firstName + " " + lastName).trim();
        if (name.isBlank()) name = "there";

        return "Hello " + name + ",\n\n"
                + body + "\n\n"
                + "Best regards,\n"
                + "The HireAI team";
    }
}