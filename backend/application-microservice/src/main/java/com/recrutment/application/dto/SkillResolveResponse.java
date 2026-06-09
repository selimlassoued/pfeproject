package com.recrutment.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response from POST /api/skill-catalog/resolve.
 *
 * Action semantics:
 *   - EXACT         : input.normalized matched an existing catalog row by name (PK lookup)
 *   - AUTO_MERGE    : the input was confidently matched to an existing skill via
 *                     (a) abbreviation lookup, (b) Levenshtein typo check,
 *                     (c) embedding similarity >= AUTO_MERGE_THRESHOLD (0.93), or
 *                     (d) LLM said "same" in the 0.75-0.93 gray band.
 *                     Silently collapse onto the suggested skill.
 *   - NEW           : no match found - caller can create a fresh catalog row.
 *                     If expectedType was provided, the LLM has confirmed this is
 *                     a real skill of the requested type.
 *   - INVALID       : the LLM classified the input as not-a-real-skill
 *                     (job title, company name, random text, ...). Caller must NOT
 *                     create a catalog row and should surface the rejection to the user.
 *   - TYPE_MISMATCH : the LLM classified the input as a real skill but of the
 *                     OTHER type (e.g. caller asked for HARD, LLM said SOFT).
 *                     Caller should show the user the suggested type so they can
 *                     re-categorize or fix the input.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkillResolveResponse {
    public enum Action { EXACT, AUTO_MERGE, NEW, INVALID, TYPE_MISMATCH }

    /** Outcome of the dedup decision - see Action javadoc. */
    private Action action;

    /** The normalized form of the input - useful for debugging. */
    private String normalized;

    /**
     * The matched (canonical) skill when action is EXACT, AUTO_MERGE, or
     * REVIEW. Null when action is NEW / INVALID / TYPE_MISMATCH.
     */
    private String matchedName;

    /** Display form of the matched skill, for UI prompts. */
    private String matchedDisplayName;

    /** Cosine similarity of the top match, 0..1. Null when no match was found. */
    private Double topScore;

    /** Top-N nearest skills (name + score) - useful for UI "did you mean" pickers. */
    private List<Suggestion> suggestions;

    /** Set when action is NEW / INVALID / TYPE_MISMATCH: what the LLM thinks this skill is. */
    private String classifiedType;

    /** Set when the LLM was consulted: one-line reason for the verdict. */
    private String classifyReason;

    /** Where the AUTO_MERGE decision came from: "EXACT", "ABBREVIATION", "LEVENSHTEIN", "EMBEDDING", "LLM". */
    private String mergeReason;

    /**
     * The display name the CALLER should use when creating a new catalog entry
     * or rendering the result in the UI. Always populated:
     *   - For EXACT / AUTO_MERGE: the existing entry's stored display name
     *     ("Spring Boot", not whatever the user typed)
     *   - For NEW: a properly capitalized form of the normalized input
     *     ("Spring Boot" from "spring boot", "JavaScript" from "javascript",
     *     "C++" from "c++", "iOS" from "ios", ...)
     *
     * This protects the catalog from accumulating ugly variants like
     * "SPRING boot" or "javascript" - whoever creates the entry first sets
     * the display name forever, so we give the cleaned form back to every
     * caller and let them save THAT.
     */
    private String suggestedDisplayName;

    /**
     * Skills this one implies / is built on. Returned by the LLM classifier when
     * action is NEW. Use to populate the `implies` column on the new catalog row
     * so the matcher can credit candidates with related skills they may not have
     * listed explicitly (e.g. a candidate listing "Spring Boot" implicitly knows
     * "Java").
     *
     * - For HARD skills the LLM may return 0 to 3 entries (canonical lowercase names)
     * - For SOFT and INVALID skills this is always empty
     * - For EXACT / AUTO_MERGE this is empty (the existing entry already has implies populated)
     */
    private java.util.List<String> suggestedImplies;

    public SkillResolveResponse(Action action, String normalized, String matchedName,
                                String matchedDisplayName, Double topScore,
                                List<Suggestion> suggestions) {
        this.action = action;
        this.normalized = normalized;
        this.matchedName = matchedName;
        this.matchedDisplayName = matchedDisplayName;
        this.topScore = topScore;
        this.suggestions = suggestions;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Suggestion {
        private String name;
        private String displayName;
        private double score;
    }
}
