package com.recrutment.application.entities;

import com.recrutment.application.converters.LanguageListConverter;
import com.recrutment.application.converters.StringListConverter;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "candidate_profiles")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CandidateProfile {

    @Id
    @Column(name = "user_id", nullable = false, unique = true)
    private String userId;

    // Step 1 — Background
    private String status;
    private String yearsOfExperience;
    private String educationLevel;

    // Step 2 — Domain
    private String domain;

    // Step 3 — Skills (ordered — index 0 = rank #1)
    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private List<String> hardSkills = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private List<String> softSkills = new ArrayList<>();

    // Languages: [{language: "French", level: "B2"}, ...]
    @Convert(converter = LanguageListConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private List<Map<String, String>> languages = new ArrayList<>();

    // Step 4 — Job Preferences. Both are multi-select: a candidate may accept
    // any of {ON_SITE, HYBRID, REMOTE} and any of {FULL_TIME, INTERNSHIP,
    // ALTERNANCE}. The ranker treats an empty list (or all options selected)
    // as "no preference" — every job stays at pref_fit = 1.0.
    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private List<String> preferredWorkArrangement = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private List<String> preferredJobType = new ArrayList<>();

    // When the candidate last opened their Preferences page. Drives the "NEW"
    // badges on skill/language chips — anything in the catalog with
    // firstSeenAt > this timestamp is shown as new. Set to "now" on candidate
    // creation so a brand-new account doesn't see every existing item as new.
    @Column(name = "last_preferences_acknowledged_at")
    private Instant lastPreferencesAcknowledgedAt;
}
