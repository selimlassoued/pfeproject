package com.zaina.interviewservice.services;

import com.zaina.interviewservice.entities.GoogleAccount;
import com.zaina.interviewservice.repos.GoogleAccountRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Google Calendar sync for recruiters.
 *
 * <p>Flow: the recruiter consents once in the browser; the frontend sends us the
 * one-time authorization {@code code}; we exchange it for a long-lived refresh
 * token and store it ({@link GoogleAccount}). From then on, every interview the
 * recruiter schedules is mirrored onto their Google Calendar with the candidate
 * invited as an attendee — Google emails the invitation automatically.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GoogleCalendarService {

    private static final String TOKEN_URL    = "https://oauth2.googleapis.com/token";
    private static final String EVENTS_URL   =
            "https://www.googleapis.com/calendar/v3/calendars/primary/events?sendUpdates=all";
    private static final String TIME_ZONE    = "Africa/Tunis";
    private static final int    DURATION_MIN = 45;
    private static final DateTimeFormatter RFC3339 =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final Pattern EMAIL =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final RestTemplate      restTemplate;
    private final GoogleAccountRepo googleAccountRepo;

    @Value("${google.client-id:}")
    private String clientId;
    @Value("${google.client-secret:}")
    private String clientSecret;
    @Value("${google.redirect-uri:http://localhost:4200/google-callback}")
    private String redirectUri;

    // ── Connection management ────────────────────────────────────────────────

    /** Exchange the one-time auth code for tokens and persist the refresh token. */
    public void connect(UUID recruiterId, String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code",          code);
        form.add("client_id",     clientId);
        form.add("client_secret", clientSecret);
        form.add("redirect_uri",  redirectUri);
        form.add("grant_type",    "authorization_code");

        Map<String, Object> tokens = postForm(form);
        String refreshToken = tokens == null ? null : (String) tokens.get("refresh_token");
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalStateException(
                    "Google did not return a refresh token. Remove the app at "
                  + "myaccount.google.com/permissions and connect again.");
        }

        GoogleAccount account = googleAccountRepo.findByRecruiterId(recruiterId)
                .orElseGet(() -> GoogleAccount.builder().recruiterId(recruiterId).build());
        account.setRefreshToken(refreshToken);
        googleAccountRepo.save(account);
        log.info("Google Calendar connected for recruiter {}", recruiterId);
    }

    public boolean isConnected(UUID recruiterId) {
        return googleAccountRepo.findByRecruiterId(recruiterId).isPresent();
    }

    public void disconnect(UUID recruiterId) {
        googleAccountRepo.findByRecruiterId(recruiterId)
                .ifPresent(googleAccountRepo::delete);
        log.info("Google Calendar disconnected for recruiter {}", recruiterId);
    }

    // ── Event creation ───────────────────────────────────────────────────────

    /**
     * Mirror an interview onto the recruiter's primary calendar.
     *
     * @return the Google event id, or {@code null} if the recruiter hasn't
     *         connected their calendar (in which case scheduling proceeds normally).
     */
    public String createInterviewEvent(UUID recruiterId, String jobTitle,
                                        String candidateEmail, LocalDateTime start,
                                        String roomUrl) {
        GoogleAccount account = googleAccountRepo.findByRecruiterId(recruiterId).orElse(null);
        if (account == null) {
            return null;   // recruiter hasn't linked Google Calendar — nothing to do
        }

        String accessToken = refreshAccessToken(account.getRefreshToken());
        LocalDateTime end  = start.plusMinutes(DURATION_MIN);

        // Only invite the candidate if we actually have a valid email — a bad
        // address would make Google reject the whole event ("Invalid attendee
        // email"). When it's missing, the event is still created, just without
        // the candidate as an attendee.
        List<Map<String, String>> attendees = new ArrayList<>();
        if (candidateEmail != null && EMAIL.matcher(candidateEmail.trim()).matches()) {
            attendees.add(Map.of("email", candidateEmail.trim()));
        } else {
            log.warn("Skipping calendar attendee — '{}' is not a valid email", candidateEmail);
        }

        Map<String, Object> event = Map.of(
                "summary",     "Interview — " + (jobTitle == null ? "Candidate" : jobTitle),
                "description", "HireAI interview.\n\nJoin the video room:\n" + roomUrl,
                "location",    roomUrl,
                "start",       Map.of("dateTime", start.format(RFC3339), "timeZone", TIME_ZONE),
                "end",         Map.of("dateTime", end.format(RFC3339),   "timeZone", TIME_ZONE),
                "attendees",   attendees,
                "reminders",   Map.of("useDefault", true)
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        @SuppressWarnings("rawtypes")
        ResponseEntity<Map> resp = restTemplate.exchange(
                EVENTS_URL, HttpMethod.POST, new HttpEntity<>(event, headers), Map.class);

        String eventId = resp.getBody() == null ? null : (String) resp.getBody().get("id");
        log.info("Google Calendar event {} created for recruiter {}", eventId, recruiterId);
        return eventId;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String refreshAccessToken(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id",     clientId);
        form.add("client_secret", clientSecret);
        form.add("refresh_token", refreshToken);
        form.add("grant_type",    "refresh_token");

        Map<String, Object> tokens = postForm(form);
        if (tokens == null || tokens.get("access_token") == null) {
            throw new IllegalStateException("Google refused to refresh the access token.");
        }
        return (String) tokens.get("access_token");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<String, Object> postForm(MultiValueMap<String, String> form) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<Map> resp = restTemplate.exchange(
                TOKEN_URL, HttpMethod.POST, new HttpEntity<>(form, headers), Map.class);
        return resp.getBody();
    }
}
