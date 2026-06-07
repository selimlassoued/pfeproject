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
            case "JOB_UPDATED"       -> notificationService.handleJobUpdated(evt);
            case "JOB_QUOTA_REACHED" -> notificationService.handleJobQuotaReached(evt);
            case "INTERVIEW_INVITE"       -> {
                notificationService.handleInterviewInvite(evt);
                notificationService.pushInterviewListChange(evt);
            }
            case "INTERVIEW_JOIN_REQUEST" -> notificationService.handleInterviewJoinRequest(evt);
            case "INTERVIEW_PROPOSAL_DECLINED" -> {
                notificationService.handleInterviewProposalDeclined(evt);
                notificationService.pushInterviewListChange(evt);
            }
            // Bell + STOMP + email — the candidate must act on these.
            case "INTERVIEW_PROPOSAL_SENT" -> {
                notificationService.handleInterviewProposalSent(evt);
                notificationService.pushInterviewListChange(evt);
            }
            // Bell + STOMP + email — the other side must accept/decline.
            case "INTERVIEW_RESCHEDULE_PROPOSED" -> {
                notificationService.handleInterviewRescheduleProposed(evt);
                notificationService.pushInterviewListChange(evt);
            }
            // Bell + STOMP + email — recruiter B must accept/decline.
            case "INTERVIEW_DELEGATION_REQUESTED" -> {
                notificationService.handleInterviewDelegationRequested(evt);
                notificationService.pushInterviewListChange(evt);
            }
            // Bell + STOMP + email — proposer needs to know the request was rejected.
            case "INTERVIEW_RESCHEDULE_DECLINED" -> {
                notificationService.handleInterviewRescheduleDeclined(evt);
                notificationService.pushInterviewListChange(evt);
            }
            // Bell + STOMP + email — recipient needs to know the request was withdrawn.
            case "INTERVIEW_RESCHEDULE_CANCELLED" -> {
                notificationService.handleInterviewRescheduleCancelled(evt);
                notificationService.pushInterviewListChange(evt);
            }
            // Bell + STOMP + email — recruiter A learns the delegate said no.
            case "INTERVIEW_DELEGATION_DECLINED" -> {
                notificationService.handleInterviewDelegationDeclined(evt);
                notificationService.pushInterviewListChange(evt);
            }
            // Bell + STOMP + email — recruiter B learns A pulled the delegation.
            case "INTERVIEW_DELEGATION_CANCELLED" -> {
                notificationService.handleInterviewDelegationCancelled(evt);
                notificationService.pushInterviewListChange(evt);
            }
            // Job-level bell to every applicant when the recruiter closes the job.
            case "JOB_CLOSED"        -> notificationService.handleJobClosed(evt);
            // Candidate flag confirmation receipt to the actor (until we add
            // an admin-broadcast list endpoint).
            case "CANDIDATE_FLAGGED" -> notificationService.handleCandidateFlagged(evt);
            // Pure data pings - the navbar imminent-interview widget reloads on
            // any of these so it appears without a refresh.
            case "INTERVIEW_SCHEDULED",
                 "INTERVIEW_CANCELLED",
                 "INTERVIEW_PROPOSAL_PICKED",
                 "INTERVIEW_PROPOSAL_CANCELLED",
                 "INTERVIEW_RESCHEDULE_CONFIRMED",
                 "INTERVIEW_DELEGATION_ACCEPTED"
                                            -> notificationService.pushInterviewListChange(evt);
            // Live offer push: STOMP refresh on both sides + persistent bell
            // on the side that has to act on the new status.
            case "OFFER_CHANGED" -> {
                notificationService.handleOfferChange(evt);
                notificationService.pushOfferChange(evt);
            }
            default -> { /* ignore */ }
        }
    }
}