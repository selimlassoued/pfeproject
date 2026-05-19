package com.recrutment.application.entities;

import com.recrutment.application.converters.LanguageListConverter;
import com.recrutment.application.converters.StringListConverter;
import jakarta.persistence.*;
import lombok.*;

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

    // Step 4 — Job Preferences
    private String preferredWorkArrangement;
    private String preferredJobType;
}
