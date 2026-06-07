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
        n.setRelatedEntityType("USER");
        n.setRelatedEntityId(userId);
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
        n.setRelatedEntityType("USER");
        n.setRelatedEntityId(userId);
        repo.save(n);

        messagingTemplate.convertAndSend("/topic/notifications." + n.getUserId(), n);
        messagingTemplate.convertAndSendToUser(n.getUserId(), "/queue/notifications", n);

        sendEmailToUser(userId, n.getTitle(), n.getBody(),BASE_URL + "/browse");
    }

    public void handleRoleUpdate(AppEventMessage evt) {
        String userId = evt.getTarget().getId();
        Map<String, Object> changes = evt.getChanges();
        List<String> oldRoles = asStringList(changes.get("oldRoles"));
        List<String> newRoles = asStringList(changes.get("newRoles"));

        String body = "Your roles have changed.\nOld: " + oldRoles + "\nNew: " + newRoles;

        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(NotificationType.ROLE_UPDATE);
        n.setTitle("Your roles were updated");
        n.setBody(body);
        n.setRelatedEntityType("USER");
        n.setRelatedEntityId(userId);
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
        n.setRelatedEntityType("APPLICATION");
        n.setRelatedEntityId((String) payload.get("applicationId"));
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
        n.setRelatedEntityType("JOB");
        n.setRelatedEntityId(jobId);
        repo.save(n);

        messagingTemplate.convertAndSend("/topic/notifications." + recruiterUserId, n);
        messagingTemplate.convertAndSendToUser(recruiterUserId, "/queue/notifications", n);
    }

    /** A recruiter was invited to join an interview's room. */
    public void handleInterviewInvite(AppEventMessage evt) {
        Map<String, Object> payload = evt.getPayload();
        if (payload == null) return;

        String recruiterId = (String) payload.get("invitedRecruiterId");
        if (recruiterId == null || recruiterId.isBlank()) return;

        String jobTitle = (String) payload.getOrDefault("jobTitle", "an interview");
        String applicationId = (String) payload.get("applicationId");

        String body = "You've been invited to join the interview for \"" + jobTitle + "\". "
                + "Open the application to enter the interview room.";

        Notification n = new Notification();
        n.setUserId(recruiterId);
        n.setType(NotificationType.INTERVIEW_INVITE);
        n.setTitle("You've been invited to an interview");
        n.setBody(body);
        n.setRelatedEntityType("APPLICATION");
        n.setRelatedEntityId(applicationId);
        repo.save(n);

        messagingTemplate.convertAndSend("/topic/notifications." + recruiterId, n);
        messagingTemplate.convertAndSendToUser(recruiterId, "/queue/notifications", n);

        String ctaUrl = applicationId != null
                ? BASE_URL + "/application/" + applicationId
                : BASE_URL + "/calendar";
        try {
            sendEmailToUser(recruiterId, n.getTitle(), n.getBody(), ctaUrl);
        } catch (Exception e) {
            log.warn("Email failed for INTERVIEW_INVITE to {}: {}", recruiterId, e.getMessage());
        }
    }

    /** The candidate couldn't make any proposed slot — notify the recruiter so
     *  they can send fresh times. Carries the candidate's reason if given. */
    /**
     * Push a lightweight "your interview list changed" ping to every user whose
     * navbar widget could be affected by an interview event - the candidate, the
     * organising recruiter, anyone delegated to, and any invited recruiters. The
     * frontend's ImminentInterview widget listens on this topic and re-fetches
     * its interview set so the live banner appears without a page refresh. It is
     * NOT a Notification (no entry in the bell, no email).
     */
    public void pushInterviewListChange(AppEventMessage evt) {
        Map<String, Object> payload = evt.getPayload();
        if (payload == null) return;

        java.util.Set<String> userIds = new java.util.LinkedHashSet<>();
        addUserIfPresent(userIds, payload.get("candidateId"));
        addUserIfPresent(userIds, payload.get("recruiterId"));
        addUserIfPresent(userIds, payload.get("fromRecruiterId"));
        addUserIfPresent(userIds, payload.get("toRecruiterId"));
        addUserIfPresent(userIds, payload.get("invitedRecruiterId"));
        Object invited = payload.get("invitedRecruiterIds");
        if (invited instanceof List<?> list) {
            for (Object item : list) addUserIfPresent(userIds, item);
        }

        if (userIds.isEmpty()) return;

        Map<String, Object> ping = Map.of(
                "type",       "LIST_CHANGED",
                "reason",     evt.getEventType() != null ? evt.getEventType() : "UNKNOWN",
                "interviewId", String.valueOf(payload.getOrDefault("interviewId", ""))
        );
        for (String uid : userIds) {
            try {
                messagingTemplate.convertAndSend("/topic/interviews.list." + uid, (Object) ping);
            } catch (Exception e) {
                log.warn("Could not push interviews.list ping to {}: {}", uid, e.getMessage());
            }
        }
    }

    private static void addUserIfPresent(java.util.Set<String> sink, Object raw) {
        if (raw == null) return;
        String s = raw.toString().trim();
        if (!s.isEmpty()) sink.add(s);
    }

    /**
     * Push a tiny "offer changed" ping to the candidate and recruiter so their
     * UIs reload the offer state in real time. NOT a Notification (no bell, no
     * email) - the changes are surfaced via the offer card refreshing.
     */
    public void pushOfferChange(AppEventMessage evt) {
        Map<String, Object> payload = evt.getPayload();
        if (payload == null) return;

        java.util.Set<String> userIds = new java.util.LinkedHashSet<>();
        addUserIfPresent(userIds, payload.get("candidateUserId"));
        addUserIfPresent(userIds, payload.get("recruiterId"));
        if (userIds.isEmpty()) return;

        Map<String, Object> ping = Map.of(
                "type",          "OFFER_CHANGED",
                "applicationId", String.valueOf(payload.getOrDefault("applicationId", "")),
                "offerId",       String.valueOf(payload.getOrDefault("offerId", "")),
                "status",        String.valueOf(payload.getOrDefault("status", ""))
        );
        for (String uid : userIds) {
            try {
                messagingTemplate.convertAndSend("/topic/offers." + uid, (Object) ping);
            } catch (Exception e) {
                log.warn("Could not push offers ping to {}: {}", uid, e.getMessage());
            }
        }
    }

    public void handleInterviewProposalDeclined(AppEventMessage evt) {
        Map<String, Object> payload = evt.getPayload();
        if (payload == null) return;

        String recruiterId = (String) payload.get("recruiterId");
        if (recruiterId == null || recruiterId.isBlank()) return;

        String jobTitle = (String) payload.getOrDefault("jobTitle", "an interview");
        String applicationId = (String) payload.get("applicationId");
        String reason = (String) payload.get("reason");

        String body = "The candidate can't make any of the times you proposed for \""
                + jobTitle + "\"."
                + (reason != null && !reason.isBlank()
                    ? " They said: \"" + reason + "\"."
                    : "")
                + " Open the application to propose new times.";

        Notification n = new Notification();
        n.setUserId(recruiterId);
        n.setType(NotificationType.INTERVIEW_PROPOSAL_DECLINED);
        n.setTitle("Candidate declined the proposed interview times");
        n.setBody(body);
        n.setRelatedEntityType("APPLICATION");
        n.setRelatedEntityId(applicationId);
        repo.save(n);

        // In-app + websocket only — NO email. Recruiters dealing with many
        // candidates would be flooded if every "can't make these times" sent
        // mail. The notification bell + the panel on the application page are
        // enough for them to act.
        messagingTemplate.convertAndSend("/topic/notifications." + recruiterId, n);
        messagingTemplate.convertAndSendToUser(recruiterId, "/queue/notifications", n);
    }

    /** A recruiter asked the organizer to be invited to an interview. */
    public void handleInterviewJoinRequest(AppEventMessage evt) {
        Map<String, Object> payload = evt.getPayload();
        if (payload == null) return;

        String organizerId = (String) payload.get("organizerId");
        if (organizerId == null || organizerId.isBlank()) return;

        String jobTitle = (String) payload.getOrDefault("jobTitle", "an interview");
        String requester = (String) payload.getOrDefault("requesterName", "A recruiter");
        String applicationId = (String) payload.get("applicationId");

        String body = requester + " is asking to join your interview for \"" + jobTitle + "\". "
                + "Open the application to invite them.";

        Notification n = new Notification();
        n.setUserId(organizerId);
        n.setType(NotificationType.INTERVIEW_JOIN_REQUEST);
        n.setTitle("A recruiter wants to join your interview");
        n.setBody(body);
        n.setRelatedEntityType("APPLICATION");
        n.setRelatedEntityId(applicationId);
        repo.save(n);

        messagingTemplate.convertAndSend("/topic/notifications." + organizerId, n);
        messagingTemplate.convertAndSendToUser(organizerId, "/queue/notifications", n);

        String ctaUrl = applicationId != null
                ? BASE_URL + "/application/" + applicationId
                : BASE_URL + "/calendar";
        try {
            sendEmailToUser(organizerId, n.getTitle(), n.getBody(), ctaUrl);
        } catch (Exception e) {
            log.warn("Email failed for INTERVIEW_JOIN_REQUEST to {}: {}", organizerId, e.getMessage());
        }
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
            n.setRelatedEntityType("JOB");
            n.setRelatedEntityId(jobId.toString());
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

    /** Safe coercion of an Object claim to List&lt;String&gt; without the
     *  raw-type cast that triggered an unchecked warning. Drops anything
     *  that isn't a String so a malformed event can't crash this listener. */
    private static List<String> asStringList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new java.util.ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof String s) out.add(s);
        }
        return out;
    }
}