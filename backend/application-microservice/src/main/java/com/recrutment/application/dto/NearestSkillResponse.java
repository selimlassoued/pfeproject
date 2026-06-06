package com.recrutment.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response shape for POST /nearest-of.
 *
 * Returns the single closest catalog row + its cosine similarity. Null when
 * no candidate in the request had an embedding cached yet — the matcher
 * treats that case as "no proxy available."
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NearestSkillResponse {
    private String name;
    private String displayName;
    /** Cosine similarity 0..1. Higher = more similar. */
    private double score;
}
