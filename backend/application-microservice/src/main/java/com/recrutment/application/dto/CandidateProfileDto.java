package com.recrutment.application.dto;

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
        String preferredWorkArrangement,
        String preferredJobType
) {
    public CandidateProfileDto() {
        this(null, null, null, null, null,
             new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
             null, null);
    }
}
