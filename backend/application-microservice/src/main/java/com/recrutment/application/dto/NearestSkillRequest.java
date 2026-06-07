package com.recrutment.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * POST /api/applications/skill-catalog/nearest-of payload.
 *
 * The matcher sends a job requirement's embedding and the list of CV-skill
 * names with non-zero confidence. Postgres runs a single indexed cosine
 * search restricted to those names and returns the closest one. Replaces the
 * Python "for each CV skill, fetch its vector and cosine it" loop in Track 1
 * — one HTTP call per requirement instead of N.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NearestSkillRequest {
    /** 768-dim vector to compare against (the job requirement's embedding). */
    private List<Float> embedding;
    /** Candidate canonical skill names to search among. */
    private List<String> candidates;
}
