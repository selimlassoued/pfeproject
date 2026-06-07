package com.recrutment.application.dto;

import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record CandidateProfileDto(
        String userId,
        @Size(max = 50) String status,
        @Size(max = 20) String yearsOfExperience,
        @Size(max = 50) String educationLevel,
        @Size(max = 100) String domain,
        @Size(max = 100, message = "max 100 hard skills") List<@Size(max = 100) String> hardSkills,
        @Size(max = 100, message = "max 100 soft skills") List<@Size(max = 100) String> softSkills,
        @Size(max = 30, message = "max 30 languages") List<Map<String, String>> languages,
        // Multi-select preferences. Both default to empty list — the ranker
        // treats empty (or "all selected") as "no preference".
        @Size(max = 10) List<@Size(max = 50) String> preferredWorkArrangement,
        @Size(max = 10) List<@Size(max = 50) String> preferredJobType,
        // When the candidate last opened their Preferences page. Frontend
        // uses this to render "NEW" badges on chips added after that moment.
        Instant lastPreferencesAcknowledgedAt
) {
    public CandidateProfileDto() {
        this(null, null, null, null, null,
             new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
             new ArrayList<>(), new ArrayList<>(),
             null);
    }
}
