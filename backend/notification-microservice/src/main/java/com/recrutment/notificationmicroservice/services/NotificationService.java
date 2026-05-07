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

import org.springframework.mail.javamail.MimeMessageHelper;
import jakarta.mail.internet.MimeMessage;

@Service
@RequiredArgsConstructor
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final UserEmailClient userEmailClient;

    private final NotificationRepo repo;
    private final JavaMailSender mailSender;
    private final ApplicationClient applicationClient;
    private static final String BASE_URL = "http://localhost:4200";

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

        sendEmailToUser(userId, n.getTitle(), n.getBody(),BASE_URL+"/browse");
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

        sendEmailToUser(userId, n.getTitle(), n.getBody(),BASE_URL + "/browse");
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

        sendEmailToUser(userId, n.getTitle(), n.getBody(),BASE_URL + "/profile");
    }

    public void handleApplicationStatusUpdate(AppEventMessage evt) {
        Map<String, Object> payload = evt.getPayload();
        String candidateUserId = (String) payload.get("candidateUserId");
        String oldStatus = (String) payload.get("oldStatus");
        String newStatus = (String) payload.get("newStatus");
        String jobTitle = (String) payload.getOrDefault("jobTitle", "your applied job");
        String body = "Your application for the position \"" + jobTitle + "\" " +
                "has been updated from " + oldStatus + " to " + newStatus + ".";

        Notification n = new Notification();
        n.setUserId(candidateUserId);
        n.setType(NotificationType.APPLICATION_STATUS_UPDATE);
        n.setTitle("Application status updated");
        n.setBody(body);
        repo.save(n);

        messagingTemplate.convertAndSend("/topic/notifications." + n.getUserId(), n);
        messagingTemplate.convertAndSendToUser(n.getUserId(), "/queue/notifications", n);
        String applicationId = (String) payload.get("applicationId"); // 👈 add this to payload
        String ctaUrl = (applicationId != null)
                ? BASE_URL + "/my-application/" + applicationId
                : BASE_URL + "/my-applications";
        // 👇 wrap this
        try {
            sendEmailToUser(candidateUserId, n.getTitle(), n.getBody(), ctaUrl);
        } catch (Exception e) {
            log.warn("Email failed for APPLICATION_STATUS_UPDATE to {}: {}", candidateUserId, e.getMessage());
        }
    }

    public void handleJobQuotaReached(AppEventMessage evt) {
        Map<String, Object> payload = evt.getPayload();
        if (payload == null) return;

        String recruiterUserId = (String) payload.get("recruiterUserId");
        if (recruiterUserId == null || recruiterUserId.isBlank() || recruiterUserId.equals("SYSTEM")) return;

        String jobTitle = (String) payload.getOrDefault("jobTitle", "the position");
        Object openings = payload.get("openings");
        Object rejected = payload.getOrDefault("rejected", 0);
        String jobId    = (String) payload.get("jobId");

        String body = "The position \"" + jobTitle + "\" has reached its hiring quota (" + openings + "/" + openings + "). " +
                "The job has been closed automatically and " + rejected + " pending application(s) were rejected. " +
                "If you need more hires, increase the quota and republish the job.";

        Notification n = new Notification();
        n.setUserId(recruiterUserId);
        n.setType(NotificationType.JOB_QUOTA_REACHED);
        n.setTitle("Position filled — " + jobTitle);
        n.setBody(body);
        repo.save(n);

        messagingTemplate.convertAndSend("/topic/notifications." + recruiterUserId, n);
        messagingTemplate.convertAndSendToUser(recruiterUserId, "/queue/notifications", n);
    }

    public void handleJobUpdated(AppEventMessage evt) {
        UUID jobId = UUID.fromString(evt.getTarget().getId());
        String jobTitle = resolveJobTitle(evt);
        String body = buildJobUpdatedBody(jobTitle, evt.getChanges());
        String ctaUrl = BASE_URL + "/jobs/" + jobId;  // 👈 add this

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

            sendEmailToUser(candidateUserId, n.getTitle(), n.getBody(), ctaUrl);  // 👈 pass ctaUrl
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

    private void sendEmailToUser(String userId, String subject, String body, String ctaUrl) {
        Map<String, String> profile = userEmailClient.getUserProfile(userId);
        if (profile == null) return;
        String email = profile.get("email");
        if (email == null || email.isBlank()) return;

        String htmlBody = buildEmailBody(profile, body, ctaUrl);

        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setTo(email);
            helper.setSubject(subject);
            helper.setText(htmlBody, true); // true = HTML
            mailSender.send(msg);
        } catch (Exception e) {
            log.warn("Failed to send notification email to {}: {}", email, e.getMessage());
        }
    }

    private String buildEmailBody(Map<String, String> profile, String body, String ctaUrl) {
        String firstName = profile.getOrDefault("firstName", "").trim();
        String lastName  = profile.getOrDefault("lastName",  "").trim();
        String name = (firstName + " " + lastName).trim();
        if (name.isBlank()) name = "there";

        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="UTF-8"/>
          <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
          <title>HireAI Notification</title>
        </head>
        <body style="margin:0;padding:0;background-color:#0b1026;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">

          <table width="100%%" cellpadding="0" cellspacing="0" role="presentation"
                 style="background:#0b1026;padding:48px 16px;">
            <tr><td align="center">
              <table width="560" cellpadding="0" cellspacing="0" role="presentation"
                     style="max-width:560px;width:100%%;">

                <tr>
                  <td align="center" style="padding-bottom:28px;">
                    <table cellpadding="0" cellspacing="0" role="presentation">
                      <tr>
                        <td style="background:rgba(255,255,255,0.06);border:1px solid rgba(121,164,233,0.22);border-radius:14px;padding:11px 26px;">
                          <span style="font-size:20px;font-weight:700;color:#fffce5;letter-spacing:-0.4px;">
                            Hire<span style="color:#79a4e9;">AI</span>
                          </span>
                        </td>
                      </tr>
                    </table>
                  </td>
                </tr>

                <tr>
                  <td style="background:linear-gradient(180deg,rgba(255,255,255,0.08),rgba(255,255,255,0.05));border:1px solid rgba(121,164,233,0.24);border-radius:28px;overflow:hidden;">
                    <table width="100%%" cellpadding="0" cellspacing="0" role="presentation">
                      <tr>
                        <td style="height:3px;background:linear-gradient(90deg,#1e40bc 0%%,#79a4e9 100%%);"></td>
                      </tr>
                    </table>
                    <table width="100%%" cellpadding="0" cellspacing="0" role="presentation"
                           style="padding:36px 40px 32px;">
                      <tr>
                        <td style="padding-bottom:6px;">
                          <p style="margin:0;font-size:22px;font-weight:700;color:#fffce5;line-height:1.3;letter-spacing:-0.02em;">
                            Hello, %s
                          </p>
                        </td>
                      </tr>
                      <tr>
                        <td style="padding-bottom:24px;">
                          <table width="36" cellpadding="0" cellspacing="0" role="presentation">
                            <tr>
                              <td style="height:3px;background:linear-gradient(90deg,#1e40bc,#79a4e9);border-radius:2px;"></td>
                            </tr>
                          </table>
                        </td>
                      </tr>
                      <tr>
                        <td style="padding-bottom:32px;">
                          <p style="margin:0;font-size:15px;color:rgba(248,250,252,0.85);line-height:1.75;">%s</p>
                        </td>
                      </tr>
                      <tr>
                        <td style="padding-bottom:36px;">
                          <table cellpadding="0" cellspacing="0" role="presentation">
                            <tr>
                              <td style="border-radius:999px;background:linear-gradient(135deg,#1e40bc,#79a4e9);box-shadow:0 10px 30px rgba(0,0,0,0.35);">
                                <a href="%s"
                                   style="display:inline-block;padding:13px 30px;font-size:14px;font-weight:600;color:#ffffff;text-decoration:none;letter-spacing:0.1px;">
                                  Open HireAI &rarr;
                                </a>
                              </td>
                            </tr>
                          </table>
                        </td>
                      </tr>
                      <tr>
                        <td style="border-top:1px solid rgba(121,164,233,0.22);padding-top:24px;">
                          <p style="margin:0;font-size:12px;color:rgba(248,250,252,0.35);line-height:1.7;">
                            You're receiving this because you have an account on HireAI.<br/>
                            If you have questions, reply to this email.
                          </p>
                        </td>
                      </tr>
                    </table>
                  </td>
                </tr>

                <tr>
                  <td align="center" style="padding-top:24px;">
                    <p style="margin:0;font-size:12px;color:rgba(248,250,252,0.25);">
                      &copy; 2026 HireAI &nbsp;&bull;&nbsp; All rights reserved
                    </p>
                  </td>
                </tr>

              </table>
            </td></tr>
          </table>
        </body>
        </html>
        """.formatted(name, body, ctaUrl);  // 👈 3 placeholders now
    }
}