package com.recrutment.application.restControllers;

import com.recrutment.application.dto.CandidateProfileDto;
import com.recrutment.application.entities.CandidateProfile;
import com.recrutment.application.repos.CandidateProfileRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

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

    @PutMapping("/me")
    public ResponseEntity<CandidateProfileDto> saveMyProfile(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody CandidateProfileDto dto) {

        String userId = jwt.getSubject();
        CandidateProfile profile = repo.findByUserId(userId)
                .orElse(CandidateProfile.builder().userId(userId).build());

        profile.setStatus(dto.status());
        profile.setYearsOfExperience(dto.yearsOfExperience());
        profile.setEducationLevel(dto.educationLevel());
        profile.setDomain(dto.domain());
        profile.setHardSkills(dto.hardSkills() != null ? dto.hardSkills() : new ArrayList<>());
        profile.setSoftSkills(dto.softSkills() != null ? dto.softSkills() : new ArrayList<>());
        profile.setLanguages(dto.languages() != null ? dto.languages() : new ArrayList<>());
        profile.setPreferredWorkArrangement(dto.preferredWorkArrangement());
        profile.setPreferredJobType(dto.preferredJobType());

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
                p.getPreferredJobType()
        );
    }
}
