package com.recrutment.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response from POST /api/skill-catalog/resolve.
 *
 * Action semantics:
 *   - EXACT       : input.normalized matched an existing catalog row by name (PK lookup)
 *   - AUTO_MERGE  : embedding similarity ≥ AUTO_MERGE_THRESHOLD (0.95) — silently
 *                   collapse onto the suggested skill, no admin action needed
 *   - REVIEW      : similarity in REVIEW_THRESHOLD .. AUTO_MERGE_THRESHOLD (0.85..0.95)
 *                   — the caller should flag for admin review or surface a
 *                   "did you mean ...?" prompt in the UI before persisting
 *   - NEW         : no near-match found above REVIEW_THRESHOLD — caller can
 *                   create a fresh catalog row for `normalized`
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkillResolveResponse {
    public enum Action { EXACT, AUTO_MERGE, REVIEW, NEW }

    /** Outcome of the dedup decision — see Action javadoc. */
    private Action action;

    /** The normalized form of the input — useful for debugging. */
    private String normalized;

    /**
     * The matched (canonical) skill when action is EXACT, AUTO_MERGE, or
     * REVIEW. Null when action is NEW.
     */
    private String matchedName;

    /** Display form of the matched skill, for UI prompts. */
    private String matchedDisplayName;

    /** Cosine similarity of the top match, 0..1. Null when action is NEW. */
    private Double topScore;

    /** Top-N nearest skills (name + score) — useful for UI "did you mean" pickers. */
    private List<Suggestion> suggestions;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Suggestion {
        private String name;
        private String displayName;
        private double score;
    }
}
