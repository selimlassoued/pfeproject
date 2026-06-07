package com.recrutment.application.restControllers;

import com.recrutment.application.dto.CandidateProfileDto;
import com.recrutment.application.entities.CandidateProfile;
import com.recrutment.application.repos.CandidateProfileRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;

@RestController
@RequestMapping("/api/applications/profile")
@RequiredArgsConstructor
public class CandidateProfileController {

    private final CandidateProfileRepo repo;

    @GetMapping("/me")
    public ResponseEntity<CandidateProfileDto> getMyProfile(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        return repo.findByUserId(userId)
                .map(this::toDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.ok(new CandidateProfileDto()));
    }

    /**
     * Records that the candidate just consulted their Preferences page. After
     * this call, "NEW" badges clear on the frontend because every item
     * currently in the catalog now has firstSeenAt ≤ lastPreferencesAcknowledgedAt.
     * Idempotent — calling it twice in a row just bumps the timestamp.
     */
    @PostMapping("/me/acknowledge")
    public ResponseEntity<Void> acknowledgePreferences(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        CandidateProfile profile = repo.findByUserId(userId)
                .orElse(CandidateProfile.builder().userId(userId).build());
        profile.setLastPreferencesAcknowledgedAt(Instant.now());
        repo.save(profile);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/me")
    public ResponseEntity<CandidateProfileDto> saveMyProfile(
            @AuthenticationPrincipal Jwt jwt,
            @jakarta.validation.Valid @RequestBody CandidateProfileDto dto) {

        String userId = jwt.getSubject();
        boolean creating = repo.findByUserId(userId).isEmpty();
        CandidateProfile profile = repo.findByUserId(userId)
                .orElse(CandidateProfile.builder().userId(userId).build());

        // First-time profile creation: seed the acknowledgment timestamp so the
        // candidate's very first Preferences visit doesn't light up every chip
        // as "NEW". Existing catalog items all have firstSeenAt ≤ this stamp.
        if (creating && profile.getLastPreferencesAcknowledgedAt() == null) {
            profile.setLastPreferencesAcknowledgedAt(Instant.now());
        }

        profile.setStatus(dto.status());
        profile.setYearsOfExperience(dto.yearsOfExperience());
        profile.setEducationLevel(dto.educationLevel());
        profile.setDomain(dto.domain());
        profile.setHardSkills(dto.hardSkills() != null ? dto.hardSkills() : new ArrayList<>());
        profile.setSoftSkills(dto.softSkills() != null ? dto.softSkills() : new ArrayList<>());
        profile.setLanguages(dto.languages() != null ? dto.languages() : new ArrayList<>());
        profile.setPreferredWorkArrangement(
                dto.preferredWorkArrangement() != null ? dto.preferredWorkArrangement() : new ArrayList<>());
        profile.setPreferredJobType(
                dto.preferredJobType() != null ? dto.preferredJobType() : new ArrayList<>());

        return ResponseEntity.ok(toDto(repo.save(profile)));
    }

    private CandidateProfileDto toDto(CandidateProfile p) {
        return new CandidateProfileDto(
                p.getUserId(),
                p.getStatus(),
                p.getYearsOfExperience(),
                p.getEducationLevel(),
                p.getDomain(),
                p.getHardSkills(),
                p.getSoftSkills(),
                p.getLanguages(),
                p.getPreferredWorkArrangement(),
                p.getPreferredJobType(),
                p.getLastPreferencesAcknowledgedAt()
        );
    }
}
