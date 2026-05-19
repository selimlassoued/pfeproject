package com.zaina.interviewservice.controllers;

import com.zaina.interviewservice.services.GoogleCalendarService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Endpoints for linking a recruiter's Google Calendar.
 * Mounted under /api/interviews/google so it routes through the gateway
 * exactly like the rest of the interview API.
 */
@RestController
@RequestMapping("/api/interviews/google")
@RequiredArgsConstructor
public class GoogleCalendarController {

    private final GoogleCalendarService googleCalendarService;

    /** Frontend posts the OAuth authorization code here after the consent screen. */
    @PostMapping("/connect")
    public ResponseEntity<Map<String, Object>> connect(@RequestBody Map<String, String> body) {
        UUID recruiterId = UUID.fromString(body.get("recruiterId"));
        googleCalendarService.connect(recruiterId, body.get("code"));
        return ResponseEntity.ok(Map.of("connected", true));
    }

    /** Has this recruiter linked Google Calendar? */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(@RequestParam UUID recruiterId) {
        return ResponseEntity.ok(
                Map.of("connected", googleCalendarService.isConnected(recruiterId)));
    }

    /** Drop the stored refresh token — interviews stop syncing. */
    @PostMapping("/disconnect")
    public ResponseEntity<Map<String, Object>> disconnect(@RequestParam UUID recruiterId) {
        googleCalendarService.disconnect(recruiterId);
        return ResponseEntity.ok(Map.of("connected", false));
    }
}
