package com.recrutment.application.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record CandidateProfileDto(
        String userId,
        String status,
        String yearsOfExperience,
        String educationLevel,
        String domain,
        List<String> hardSkills,
        List<String> softSkills,
        List<Map<String, String>> languages,
        // Multi-select preferences. Both default to empty list — the ranker
        // treats empty (or "all selected") as "no preference".
        List<String> preferredWorkArrangement,
        List<String> preferredJobType,
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
